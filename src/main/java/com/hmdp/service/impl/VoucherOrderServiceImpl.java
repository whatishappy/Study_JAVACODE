package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final String STREAM_QUEUE_NAME = "stream.orders";

    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //优雅关闭标志
    private volatile boolean running = true;

    @PostConstruct
    private void init() {
        //确保Stream和消费者组存在
        try {
            //MKSTREAM: 如果Stream不存在则自动创建
            stringRedisTemplate.opsForStream().createGroup(STREAM_QUEUE_NAME, ReadOffset.from("0"), "g1");
        } catch (Exception e) {
            //BUSYGROUP表示组已存在，忽略
            log.info("消费者组g1初始化: {}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdownNow();
        log.info("秒杀订单处理线程已关闭");
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (running) {
                try {
                    // 1. 获取消息队列中的订单消息 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    // 2. 判断获取消息是否成功
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3. 解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4. 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ack确认 sack stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_QUEUE_NAME, "g1", record.getId());

                } catch (Exception e) {
                    if (!running) {
                        //服务正在关闭，不再处理异常
                        break;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (running) {
                try {
                    // 1. 获取pending-list中的订单 xreadgroup group g1 c1 count 1 block 2000 streams stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_QUEUE_NAME, ReadOffset.from("0"))
                    );
                    // 2. 判断是否有消息
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 3. 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 4. 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 5. ack确认
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_QUEUE_NAME, "g1", record.getId());
                } catch (Exception e) {
                    if (!running) {
                        //服务正在关闭，不再处理异常
                        break;
                    }
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        }
    }

    private volatile IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //proxy可能尚未初始化（启动后未有seckillVoucher调用，但有残留消息）
        IVoucherOrderService proxyInstance = proxy;
        if (proxyInstance == null) {
            log.error("代理对象未初始化，无法创建订单，消息将重试");
            throw new RuntimeException("代理对象未初始化");
        }
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxyInstance.createVoucherOder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 生成订单id
        long orderId = redisWorker.nextId("order");

        // 1. 执行lua脚本，校验资格、扣库存、发消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                Long.toString(orderId)
        );

        // 2. 判断返回结果
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3. 获取代理对象（子线程事务需要）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createVoucherOder(VoucherOrder voucherOrder) {
        // 一人一券
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已购买过该优惠券");
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 创建订单
        save(voucherOrder);
    }
}
