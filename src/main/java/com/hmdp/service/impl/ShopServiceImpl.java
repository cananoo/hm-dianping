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
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithLogicalExpire(id);
        if (shop==null){
            return Result.fail("不存在此商铺!");
        }
        return Result.ok(shop);
    }


   //互斥锁解决缓存击穿
    public Shop queryWithMutexLock(Long id) {
        //1.从redis查询商铺缓存 (这里演示string的方法)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return  shop;
        }
        // 判断命中的是否为空值
        if (shopJson!=null){
            //返回错误信息
            return null;
        }
        Shop shop = null;
        try {
            //4.1缓存重建
            //4.1.1获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY+id);
            //4.1.2判断是否成功
            //4.1.3失败，则休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutexLock(id);
            }
            //4.1.4成功，则根据id查询数据库
            shop = getById(id);
            //5.不存在，返回空值(缓存穿透解决)
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
            //6.存在，写入redis
            //Thread.sleep(5000); 测试是否只查询数据库一次
            String shopStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopStr,CACHE_SHOP_TTL, TimeUnit.MINUTES); //添加一个有效时长
        } catch (InterruptedException e) {
            throw  new RuntimeException(e);
        }finally {
            //4.1.5释放互斥锁
            unLock(LOCK_SHOP_KEY+id);
        }
        //7.返回
        return shop;
    }


    //利用setns实现互斥
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);     //flag 可能为null，用hotool工具类可以判断null为false;
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    //线程池，用来缓存重建
    private  static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿问题
    public Shop queryWithLogicalExpire(Long id) {
        //1.从redis查询商铺缓存 (这里演示string的方法)
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，直接返回
            return  null;
        }
         //4.命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        //未过期，直接返回店铺信息
         if (expireTime.isAfter(LocalDateTime.now())){
             return  shop;
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
                this.saveShop2Redis(id,10L);
            } catch (Exception e){
                  throw  new RuntimeException(e);
            }finally {
                //释放锁
                unLock(LOCK_SHOP_KEY + id);
            }
        });

        }

        //返回过期的店铺信息
       return shop;
    }

    //封装存储店铺信息及其过期时间
   private void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
       Shop shop = getById(id);
       //封装逻辑过期时间
       RedisData redisData = new RedisData(); //封装了店铺及店铺的逻辑过期时间的类
       redisData.setData(shop);
       redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
       //写入redis
       stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
   }



    @Override
    @Transactional // 事务操作
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空!");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return  Result.ok();
    }
}
