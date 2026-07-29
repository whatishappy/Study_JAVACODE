package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
    @Override
    public Result queryById(Long id){
<<<<<<< HEAD

        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
=======
        String cacheKey = CACHE_SHOP_KEY + id;
        //从redis中获取商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
        //存在，返回商铺信息
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
>>>>>>> fix-login-token-401-error-AYiLIu
        }

<<<<<<< HEAD
=======
            return Result.fail("商铺不存在!!!");
        }
        //数据库存在，将查询的商铺信息存入redis
        stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(shop));
        //返回商铺信息
>>>>>>> fix-login-token-401-error-AYiLIu
        return Result.ok(shop);
        //结束业务
    }

    public Shop queryWithMutex(Long id){
        //从redis中获取商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY);
        //存在，返回商铺信息
        if (StrUtil.isNotBlank(shopJson)) { //由于isnotblank方法 判断字符串有数据才在为true，
            // 其他所有情况都为false
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //在执行查询数据库库之前还需要判断redis是否为null
        //如果为bull，直接返回
        if ("".equals(shopJson)) {
            return null;
        }

        Shop shop =null;

        //TODO 4缓存重建
        //4.1 获取互斥锁
        tryLock(LOCK_SHOP_KEY+id);
        try {
            boolean isLock =tryLock(LOCK_SHOP_KEY+id);
            //4.2判断互斥锁是否成功
            if(!isLock){
                //4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4，4成功，根据id查询数据库
            //缓存不存在 根据id查询数据库
             shop = getById(id);
            Thread.sleep(200);
            //不存在，返回错误
            if (shop==null){

                //将null写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

                return null;
            }
            //6.存在，存入redis


            //数据库存在，将查询的商铺信息存入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(LOCK_SHOP_KEY+id);
        }

        //返回商铺信息
        return shop;
    }

    @Override
    @Transactional
    public Result Update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不存在");
        }

        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        //返回结果

        return Result.ok();
    }


    //获取锁
private boolean tryLock(String key){
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

//释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
