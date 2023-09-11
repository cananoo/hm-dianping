package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryShopTypes() {
        String key = CACHE_SHOP_KEY+"type";
        List<Object> typeRes = stringRedisTemplate.opsForHash().values(key);
        if (!typeRes.isEmpty()) {
            List<ShopType> typeList = new ArrayList<>();
            for( Object type :  typeRes){
                ShopType ty = JSONUtil.toBean(type.toString(), ShopType.class);
                typeList.add(ty);
            }
            return  Result.ok(typeList);
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (typeList.isEmpty()) {
            return Result.fail("类型列表不存在!");
        }

        for (ShopType type: typeList) {
            stringRedisTemplate.opsForHash().put(key,type.getId().toString(),JSONUtil.toJsonStr(type));
        }
        return Result.ok(typeList);
    }
}
