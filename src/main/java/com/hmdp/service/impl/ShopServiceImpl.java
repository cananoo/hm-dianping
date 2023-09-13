package com.hmdp.service.impl;


import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;


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
    public Result queryById(Long id) {

        // 解决缓存穿透(lambda表达式，传递方法)
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //逻辑删除解决缓存击穿问题
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop==null){
            return Result.fail("不存在此商铺!");
        }
        return Result.ok(shop);
    }


//   //互斥锁解决缓存击穿
//    public Shop queryWithMutexLock(Long id) {
//        //1.从redis查询商铺缓存 (这里演示string的方法)
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //3.存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return  shop;
//        }
//        // 判断命中的是否为空值
//        if (shopJson!=null){
//            //返回错误信息
//            return null;
//        }
//        Shop shop = null;
//        try {
//            //4.1缓存重建
//            //4.1.1获取互斥锁
//            boolean isLock = tryLock(LOCK_SHOP_KEY+id);
//            //4.1.2判断是否成功
//            //4.1.3失败，则休眠并重试
//            if (!isLock) {
//                Thread.sleep(50);
//                return queryWithMutexLock(id);
//            }
//            //4.1.4成功，则根据id查询数据库
//            shop = getById(id);
//            //5.不存在，返回空值(缓存穿透解决)
//            if (shop == null) {
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            }
//            //6.存在，写入redis
//            //Thread.sleep(5000); 测试是否只查询数据库一次
//            String shopStr = JSONUtil.toJsonStr(shop);
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,shopStr,CACHE_SHOP_TTL, TimeUnit.MINUTES); //添加一个有效时长
//        } catch (InterruptedException e) {
//            throw  new RuntimeException(e);
//        }finally {
//            //4.1.5释放互斥锁
//            unLock(LOCK_SHOP_KEY+id);
//        }
//        //7.返回
//        return shop;
//    }


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
