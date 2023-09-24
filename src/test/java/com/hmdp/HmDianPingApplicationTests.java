package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

     @Resource
    private ShopServiceImpl shopService;

     @Resource
     private StringRedisTemplate stringRedisTemplate;

     @Test
     void testShopSave(){
         Shop byId = shopService.getById(1L);
         cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L,byId,10L, TimeUnit.SECONDS);
     }

     @Test
    void loadTypeData(){
         // 查询店铺信息
         List<Shop> list = shopService.list();
         // 把店铺分组，按照typeId分组，id一致的放到一个集合
         // 利用Stream流的功能实现分组
          Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
         // 分批完成写入Redis
         for (Map.Entry<Long,List<Shop>> entry : map.entrySet()) {
              // 获取类型id
             Long typeId = entry.getKey();
             String key = "shop:geo:" + typeId;
             // 获取同类型的店铺
             List<Shop> value = entry.getValue();
             // 写入redis GEOADD key lon lat member
             List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
             for (Shop shop: value) {

                 // 法一：  stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());

                 // 法二：

                 locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));

             }
             stringRedisTemplate.opsForGeo().add(key,locations);
         }
     }


}
