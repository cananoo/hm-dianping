package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


public class SimpleRedisLock  implements ILock{

    //传递的key
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取锁
        String key = ID_PREFIX + KEY_PREFIX + name;

        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId() ;

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);

        return  BooleanUtil.isTrue(success);  //防止自动拆箱为null
    }

    @Override
    public void unLock() {
        String key = KEY_PREFIX + name;
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId() ;
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(key);
          //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(key);
        }
    }
}
