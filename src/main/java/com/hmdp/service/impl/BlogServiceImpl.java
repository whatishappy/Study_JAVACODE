package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;



    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLike(blog);

        });
        return Result.ok(records);
    }


    @Override
    public Result queryById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);

        //查询用户是否点赞（只查询状态，不执行点赞）
        isBlogLike(blog);
        return Result.ok(blog);
    }


    private void isBlogLike(Blog blog){
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //未登录用户，标记为未点赞
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否点赞（key要拼blogId，否则所有blog共用一个ZSet）
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        //更新用户点赞状态
        blog.setIsLike(score != null);
    }



    @Override
    public Result likeBlog(Long id) {
        //1获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2判断用户是否已点赞（key要拼blogId）
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        //3如果未点赞,可以点赞
        if (score == null){
            //3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked +1").eq("id", id).update();
            //3.2 保存用户到Redis的ZSet集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4如果已经点赞，取消点赞
            //4.1 从数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked -1").eq("id", id).update();
            //4.2 把用户从Redis的ZSet集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }



    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5 的点赞用户 zrange by key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        String idStr = StrUtil.join(",", ids);
        //根据用户id查询用户并转化为DTO，使用 FIELD(id, ...) 保持点赞顺序
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("order by field (id," +idStr +")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);

        if (!isSuccess) {
           return Result.fail("新增笔记失败!!");
        }
        //推送给粉丝
        //1.查询笔记作者所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //2.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = FOLLOW_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.寻找当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询收件箱 zreverrangeByscore key max min limit offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples
                = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.解析数据:blogId、minTIme（最小时间戳）、offset
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }

        List<Long> ids = new ArrayList<>(typedTuples.size());

        //经历for循环后获得最小的时间戳，确保获取最上层的信息
        long minTime = 0;
        int os =1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取blogid
            String idStr = typedTuple.getValue();

            ids.add(Long.valueOf(idStr));
            //获取时间戳
             long time = typedTuple.getScore().longValue();
             if (minTime == time){
                 os++;// 当获得的最小时间戳与上一个对比，看看是不是同时间戳，
                 // 如果是，则为同一篇博客或者同时发布的博客，偏移量+1
             }else {
                 minTime=time; //不同，则重置最小时间戳
                 os = 1; //重置为当前为最新
             }

        }

        //4.查询博客封装为blog集合
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs =
                query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        //每一篇博客又要有这部操作
        for (Blog blog : blogs) {
            //查询blog有关的用户
            queryBlogUser(blog);
            //查询用户是否点赞（只查询状态，不执行点赞）
            isBlogLike(blog);

        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null){
            blog.setName("未知用户");
            blog.setIcon("");
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
