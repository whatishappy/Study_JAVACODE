package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id){

        /*//使用互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }*/

        //使用逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    // 逻辑过期方式（需预热）
    public Shop queryWithLogicExpire(Long id) {
        return cacheClient.queryWithLogicExpire(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY,
                id, Shop.class,
                this::getById,
                2L,                        // 逻辑过期 2 秒
                TimeUnit.SECONDS
        );
    }

    public void save2Shop(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    // 互斥锁方式
    public Shop queryWithMutex(Long id) {
        return cacheClient.queryWithMutex(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY,
                id, Shop.class,
                this::getById,              // 函数引用
                CACHE_SHOP_TTL,             // 正常缓存 30 分钟
                CACHE_NULL_TTL,             // 空值缓存 2 分钟
                TimeUnit.MINUTES
        );
    }

    @Override
    @Transactional
    public Result Update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不存在");
        }

        //更新数据库
        boolean isSuccess = updateById(shop);
        if (!isSuccess) {
            return Result.fail("店铺不存在");
        }
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        //返回结果

        return Result.ok();
    }

}
