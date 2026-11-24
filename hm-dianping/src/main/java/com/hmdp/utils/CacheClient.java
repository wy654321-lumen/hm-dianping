package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //存普通数据
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //存带逻辑时间的数据
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //解决缓存穿透
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> abFallback
            , Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String JSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(JSON)) {
            return JSONUtil.toBean(JSON, type);
        }
        if (JSON != null) {//判不为空后就是null和空串
            return null;
        }
        R r = abFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);//解决穿透存空值
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    //互斥锁解决击穿问题
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//避免空指针
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public  <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> abFallback
            , Long time, TimeUnit unit) {//击穿

        String key = keyPrefix + id;
        String JSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(JSON)) {

            return null;
        }
            //命中json反序列化为对象
            RedisData redisData = JSONUtil.toBean(JSON, RedisData.class);
//        JSONObject shop = (JSONObject)redisData.getData();
            R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();
            //未过期返回店铺信息
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            //过期就缓存重建
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            if (isLock) {
                //成功就开启独立线程  线程池
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                      R r1 = abFallback.apply(id);
                      this.setWithLogicalExpire(key,r1,time,unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(lockKey);
                    }

                });
            }
            return r;
        }
}