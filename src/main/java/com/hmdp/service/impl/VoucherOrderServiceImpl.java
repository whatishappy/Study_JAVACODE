package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private RedissonClient redissonClient;
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //TODO1.查询优惠券id
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //TODO2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //2.1 秒杀未开始
            return Result.fail("秒杀活动未开始!!");
        }

        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //2.2 秒杀已结束
            return Result.fail("秒杀活动已结束!!!");
        }

        //2.3 判断秒杀券库存
        if (voucher.getStock() < 1){
            //不充足，返回异常结果
            return Result.fail("秒杀券已被抢空~");
        }

        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!isLock){
            //获取锁失败
            //返回失败
            return Result.fail("请重试!!!");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public  Result createVoucherOder(Long voucherId) {
        /* 一人一券*/
        //判断是否是同一用户抢券是同一用户返回失败
        Long id = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
        if (count >0){
            return Result.fail("用户已抢过一次券");
        }


        //充足，扣减库存
        boolean success= seckillVoucherService.update()
                .setSql("stock = stock -1") //set stock = stock -1
                .eq("voucher_id", voucherId).gt("stock",0) // where id = ? and stock > 0
                .update();

        //库存扣除失败
        if(!success){
            return Result.fail("库存不足!");
        }
        //TODO创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //设置代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // TODO返回订单id
        return Result.ok(voucherOrder);
    }
}
