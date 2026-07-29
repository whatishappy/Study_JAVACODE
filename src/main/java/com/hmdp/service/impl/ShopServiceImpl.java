package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id){
        String cacheKey = CACHE_SHOP_KEY + id;
        //从redis中获取商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        //存在，返回商铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //缓存不存在 根据id查询数据库
        Shop shop = getById(id);
        if (shop==null){

            return Result.fail("商铺不存在!!!");
        }
        //数据库存在，将查询的商铺信息存入redis
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop));
        //返回商铺信息
        return Result.ok(shop);
        //结束业务
    }

}
