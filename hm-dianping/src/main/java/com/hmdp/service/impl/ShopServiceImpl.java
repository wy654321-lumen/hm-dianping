package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.Key;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    public IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
//       Shop shop =  cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,
//                this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);//穿透
//        Shop shop = queryWithMutex(id);// 互斥锁解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);// 逻辑过期解决缓存击穿
        Shop shop =  cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,
                this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);//逻辑过期解决缓存击穿
        if( shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

//    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    private Shop queryWithLogicalExpire(Long id) {//击穿
//        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+ id);
//        if(StrUtil.isBlank(shopJSON)){
//
//            return null;
//        }
//        //命中json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
////        JSONObject shop = (JSONObject)redisData.getData();
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //未过期返回店铺信息
//        if(expireTime.isAfter(LocalDateTime.now())){
//            return shop;
//        }
//        //过期就缓存重建
//        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
//        boolean isLock = tryLock(lockKey);
//        if(isLock){
//            //成功就开启独立线程  线程池
//            CACHE_REBUILD_EXECUTOR.submit(() ->{
//                try {
//                    this.saveShopRedis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    unLock(lockKey);
//                }
//
//            });
//        }
//        return shop;
//    }

//    private Shop queryWithPassThrough(Long id) {//穿透
//        String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+ id);
//        if(StrUtil.isNotBlank(shopJSON)){
//            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
//            return shop;
//        }
//        if(shopJSON != null){//判不为空后就是null和空串
//            return null;
//        }
//        Shop shop = getById(id);
//        if(shop == null){
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,"",
//                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);//解决穿透存空值
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,JSONUtil.toJsonStr(shop),
//                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    private Shop queryWithMutex(Long id) {//击穿
        String key = RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            String shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+ id);
            if(StrUtil.isNotBlank(shopJSON)){
                return JSONUtil.toBean(shopJSON, Shop.class);
            }
            if(shopJSON != null){//判不为空后就是null和空串
                return null;
            }
            if (!tryLock(key)) {//加互斥锁解决热点key过期导致大量访问崩溃
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 只有拿到锁的线程才进入内层 try，也才允许在 finally 里释放这把锁
            try {
                // 双重检查：等锁期间别的线程可能已经把缓存重建好了
                shopJSON = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+ id);
                if(StrUtil.isNotBlank(shopJSON)){
                    return JSONUtil.toBean(shopJSON, Shop.class);
                }
                shop = getById(id);
                Thread.sleep(200);//模拟重建延时
                if(shop == null){
                    stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,"",
                            RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);//解决穿透存空值
                    return null;
                }
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+ id,JSONUtil.toJsonStr(shop),
                        RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } finally {
                unLock(key);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return shop;
    }
    //互斥锁解决击穿问题
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//避免空指针
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    public void saveShopRedis(Long id, Long expireSeconds){
        // 1. 查数据库
        Shop shop = getById(id);
        // 2. 模拟重建缓存的耗时（真实业务里可能是多次查询、拼装、远程调用）
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 3. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 4. 写入 Redis，注意：不设置 TTL，永不物理过期
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }




    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺ID不能为空");
        }
        shopService.updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
