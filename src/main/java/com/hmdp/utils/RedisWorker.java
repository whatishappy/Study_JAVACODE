package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1767225600L;
    private static final int COUNT_BITS = 32;


    public long nextId(String keyPrefix) {
        //1.生成时间戳
        //当前时间减去时间错
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;


        //2.生成序列号
        //获取当前时期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);//拼接当天的字符串,避免订单超过2的32次方的上限


        //3.拼接字符串
        //由于拼接long类型，使用左右移动方式拼接
        return timestamp << COUNT_BITS | count;


    }


    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);

    }
}
