package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    /** 缓存重建线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);
    /** 锁的默认TTL（秒） */
    private static final long LOCK_TTL = 10L;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // ==================== 写操作 ====================
    /**
     * 设置普通缓存（带TTL + 随机偏移防雪崩）
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        long ttl = unit.toSeconds(time);
        long randomOffset = ThreadLocalRandom.current().nextLong(0, ttl / 3);  // ±1/3 偏移
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), ttl + randomOffset, TimeUnit.SECONDS);
    }
    /**
     * 设置逻辑过期缓存（不设Redis物理TTL，过期靠expireTime字段）
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // ==================== 读操作 ====================
    /**
     * 互斥锁查询 —— 防击穿 + 防穿透 + 防雪崩
     *
     * @param keyPrefix    缓存key前缀
     * @param lockPrefix   锁key前缀
     * @param id           业务ID
     * @param type         返回值类型
     * @param dbFallback   数据库查询函数
     * @param ttl          正常缓存TTL
     * @param nullTtl      空值缓存TTL
     * @param unit         时间单位
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, String lockPrefix,
            ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long ttl, Long nullTtl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        String lockKey = lockPrefix + id;
        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 穿透保护：空值标记
        if ("".equals(json)) {
            return null;
        }
        // 2. 互斥锁循环
        while (true) {
            if (!tryLock(lockKey)) {
                try { Thread.sleep(50); } catch (InterruptedException e) { throw new RuntimeException(e); }
                continue;
            }
            try {
                // 3. DoubleCheck
                json = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(json)) {
                    return JSONUtil.toBean(json, type);
                }
                if ("".equals(json)) {
                    return null;
                }
                // 4. 查数据库
                R result = dbFallback.apply(id);
                // 5. 穿透保护：写空值
                if (result == null) {
                    stringRedisTemplate.opsForValue()
                            .set(cacheKey, "", nullTtl, TimeUnit.SECONDS);
                    return null;
                }
                // 6. 写缓存（随机TTL防雪崩）
                this.set(cacheKey, result, ttl, unit);
                return result;
            } finally {
                unlock(lockKey);
            }
        }
    }
    /**
     * 逻辑过期查询 —— 防击穿 + 防雪崩（需提前预热缓存）
     *
     * @param keyPrefix    缓存key前缀
     * @param lockPrefix   锁key前缀
     * @param id           业务ID
     * @param type         返回值类型
     * @param dbFallback   数据库查询函数
     * @param ttl          逻辑过期时间
     * @param unit         时间单位
     */
    public <R, ID> R queryWithLogicExpire(
            String keyPrefix, String lockPrefix,
            ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {
        String cacheKey = keyPrefix + id;
        String lockKey = lockPrefix + id;
        // 1. 查缓存
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        // 2. 缓存未命中 → 走互斥锁兜底
        if (StrUtil.isBlank(json)) {
            return queryWithMutexAndLogicWrite(
                    cacheKey, lockKey, id, type, dbFallback, ttl, unit);
        }
        // 3. 命中 → 判断是否逻辑过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 拿到实际数据（可能是JSONObject，需二次转换）
        R result = extractData(redisData, type);
        // 4. 未过期 → 直接返回
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return result;
        }
        // 5. 已过期 → 获取锁，异步重建
        if (tryLock(lockKey)) {
            // DoubleCheck
            json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(json)) {
                RedisData doubleCheckData = JSONUtil.toBean(json, RedisData.class);
                if (doubleCheckData.getExpireTime() != null
                        && doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unlock(lockKey);
                    return extractData(doubleCheckData, type);
                }
            }
            // 异步重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newResult = dbFallback.apply(id);
                    if (newResult != null) {
                        this.setWithLogicExpire(cacheKey, newResult, ttl, unit);
                    }
                } catch (Exception e) {
                    log.error("逻辑过期缓存重建失败，key={}", cacheKey, e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6. 返回旧数据（过期但可用）
        return result;
    }
    // ==================== 内部工具方法 ====================
    /**
     * 缓存未命中时走互斥锁查DB，然后写逻辑过期缓存
     */
    private <R, ID> R queryWithMutexAndLogicWrite(
            String cacheKey, String lockKey,
            ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {
        while (true) {
            if (!tryLock(lockKey)) {
                try { Thread.sleep(50); } catch (InterruptedException e) { throw new RuntimeException(e); }
                continue;
            }
            try {
                String json = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(json)) {
                    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                    return extractData(redisData, type);
                }
                R result = dbFallback.apply(id);
                if (result != null) {
                    this.setWithLogicExpire(cacheKey, result, ttl, unit);
                }
                return result;
            } finally {
                unlock(lockKey);
            }
        }
    }
    /**
     * 从 RedisData 中提取业务数据（处理 JSONObject 嵌套问题）
     */
    @SuppressWarnings("unchecked")
    private <R> R extractData(RedisData redisData, Class<R> type) {
        Object data = redisData.getData();
        if (data == null) return null;
        // data 经过 JSON 反序列化后是 JSONObject，需要二次转换
        if (data instanceof JSONObject) {
            return JSONUtil.toBean((JSONObject) data, type);
        }
        return (R) data;
    }
    /**
     * 获取互斥锁（SETNX）
     */
    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }
    /**
     * 释放锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
