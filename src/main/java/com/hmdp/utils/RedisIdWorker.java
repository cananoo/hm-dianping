package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1643767322L;
    private static final long COUNT_BITS = 32L;
    public long nextId(String keyPrefix){
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
         long timeStamp = nowEpochSecond- BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1 生成当前日期，精确到天
        String day = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd")); //以:分割，方便统计redis数量
        // 2.2自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + day); //步长默认1

        // 3.拼接并返回
        return timeStamp << COUNT_BITS | count;  //或运算实现高低位拼接
    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 2, 2, 2, 2, 2);
//        long epochSecond = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(epochSecond);     //1643767322L
//    }

}
