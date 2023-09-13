package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

      //①普通存储
    public void set (String key, Object value, Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //②带逻辑过期存储
    public void setWithLogicalExpire (String key, Object value, Long time , TimeUnit unit){
       RedisData redisData = new RedisData();
       redisData.setData(value);
       redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //③解决缓存穿透的取（设空值），定义泛型，使其成为通用方法,用函数式编程传递数据库操作逻辑
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time , TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis查询商铺缓存 (这里演示string的方法)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return  JSONUtil.toBean(json,type);
        }
        // 判断命中的是否为空值
        if (json!=null){
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回空值(缓存穿透解决)
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        //6.存在，写入redis
       this.set(key,r,time,unit);
        //7.返回
        return r;
    }

    //④解决缓存击穿问题
    //线程池，用来缓存重建
    private  static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time , TimeUnit unit) {
        String key = keyPrefix+id;
        //1.从redis查询商铺缓存 (这里演示string的方法)
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，直接返回
            return  null;
        }
        //4.命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        //未过期，直接返回店铺信息
        if (expireTime.isAfter(LocalDateTime.now())){
            return  r;
        }
        //已过期，需要缓存重建
        //6.缓存重建
        //获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        //判断是否获取锁成功
        if (isLock){
            //成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    R r1 = dbFallBack.apply(id);
                    setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e){
                    throw  new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }
        //返回过期的店铺信息
        return r;
    }
    //利用setns实现互斥
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);     //flag 可能为null，用hotool工具类可以判断null为false;
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
