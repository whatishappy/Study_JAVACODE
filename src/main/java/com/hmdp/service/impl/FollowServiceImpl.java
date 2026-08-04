package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private IUserService userService;
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;

        //1.先查Redis Set，sismember是O(1)
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());
        if (Boolean.TRUE.equals(isMember)) {
            return Result.ok(true);
        }

        //2.缓存未命中，查数据库
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        boolean followed = count > 0;

        //3.数据库有记录但缓存没有，回写缓存
        if (followed) {
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        }

        return Result.ok(followed);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();

        //不能关注自己
        if (userId.equals(followUserId)) {
            return Result.fail("不能关注自己");
        }

        String key = FOLLOW_KEY + userId;

        if (isFollow) {
            //关注前先检查是否已关注，防止重复关注
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());
            if (Boolean.TRUE.equals(isMember)) {
                return Result.fail("已关注该用户");
            }
            //缓存未命中，查数据库兜底
            Integer count = query().eq("user_id", userId)
                    .eq("follow_user_id", followUserId).count();
            if (count > 0) {
                return Result.fail("已关注该用户");
            }
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (!success) {
                return Result.fail("关注失败");
            }
            //写入缓存
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            //取消关注，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
            );
            //DB删除成功才移除缓存
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        //从redis中求交集
        String key2 = FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //将解析的set转化为id集合(String 转化为 Long 类型)
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询用户
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOList = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回结果

        return Result.ok(userDTOList);
    }
}
