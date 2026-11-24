package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    /**
     * 预热缓存：把店铺数据按「逻辑过期」格式写入 Redis（不设 TTL）
     * @param id 店铺id
     * @param expireSeconds 逻辑过期时间，单位秒
     */
    void saveShopRedis(Long id, Long expireSeconds);
}
