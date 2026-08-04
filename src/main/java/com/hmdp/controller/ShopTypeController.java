package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        try {
            String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
            if (StrUtil.isNotBlank(json)) {
                List<ShopType> typeList = JSONUtil.toList(json, ShopType.class);
                // 缓存命中但列表为空，视为缓存失效，降级到数据库
                if (typeList != null && !typeList.isEmpty()) {
                    return Result.ok(typeList);
                }
            }
        } catch (Exception e) {
            log.error("查询商铺类型缓存失败，降级到数据库", e);
        }

        // Redis 缓存未命中或异常，查询数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();

        // 不为空时才写 Redis 缓存，空列表不缓存避免脏数据
        if (typeList != null && !typeList.isEmpty()) {
            try {
                stringRedisTemplate.opsForValue().set(
                        CACHE_SHOP_TYPE_KEY,
                        JSONUtil.toJsonStr(typeList),
                        CACHE_SHOP_TTL,
                        TimeUnit.MINUTES
                );
            } catch (Exception e) {
                log.error("写入商铺类型缓存失败", e);
            }
        }

        return Result.ok(typeList != null ? typeList : Collections.emptyList());
    }
}
