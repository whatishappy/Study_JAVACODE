package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // TODO1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            //不存在token，直接放行
            return true;
        }
        //兼容 Apifox 等客户端自动添加的 Bearer 前缀
        if (token.startsWith("Bearer ")){
            token = token.substring(7);
        }

        String key = RedisConstants.LOGIN_USER_KEY;

        //TODO2.基于token获取reids中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().
                entries(key + token);
        //判断用户是否存在
        if (userMap.isEmpty()){
            //不存在用户,直接放行交给登录拦截器
            return true;
        }
        //  用户存在，保存用户信息到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        //刷新token有效期
        stringRedisTemplate.expire(key + token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
