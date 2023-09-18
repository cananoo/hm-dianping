package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock  implements ILock{

    //传递的key
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    //Lua脚本的初始化，开始就加载好，无须每次调用都进行加载
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;     //Long为脚本的返回值类型
    static {
        UNLOCK_SCRIPT =  new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取锁
        String key =  KEY_PREFIX + name;

        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId() ;

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);

        return  BooleanUtil.isTrue(success);  //防止自动拆箱为null
    }
    @Override
    public void unLock() {
         //调用Lua脚本 (满足原子性)
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name), //单元素集合
                ID_PREFIX + Thread.currentThread().getId()
        );
        System.out.println("oo");
    }

    /*
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
    */

}
