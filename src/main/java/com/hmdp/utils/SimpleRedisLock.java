package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock{

    /*分布式锁需要在不同JVM中获取不同的锁
    * 因此需要传入相关数据，区分各个业务中的锁
    * 保证锁的原子性*/

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static  final String KEY_PREFIX ="lock";
    private static  final String ID_PREFIX= UUID.randomUUID().toString(true) +"-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        //初始化redisscript对象，载入lua脚本
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //查询lua脚本
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设定返回类型，执行成功返回1，否则返回0
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    @Override
    public boolean tryLcok(long timeoutSec) {
        //获取线程id
        //用于获取锁业务锁的标识，防止别其他线程提前释放
        //多台jvm中Thread的id会自增，当线程很多时，很有可能出现线程id重复
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);

        //涉及拆箱装箱操作，包装类若为null，则会抛出空指针异常
        //因此我们需要是TRUE来确保
        //success -> ture 拆箱=> true
        //success -> false 拆箱=> false
        //success -> null 拆箱=> false
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock(){

        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());
    }

/*    @Override
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //判断
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }*/

}
