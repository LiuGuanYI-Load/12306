package org.jav.train12306.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.dao.entity.CarriageDO;
import org.jav.train12306.dao.mapper.CarriageMapper;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.cache.core.CacheLoader;
import org.jav.train12306.framework.starter.cache.toolkit.CacheUtil;
import org.jav.train12306.service.CarriageService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_CARRIAGE;
import static org.jav.train12306.common.constant.RedisKeyConstant.LOCK_QUERY_CARRIAGE_NUMBER_LIST;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

// 标记这是一个 Spring 服务类，会被 Spring 容器管理
@Service
// Lombok 注解，自动生成带有 final 字段的构造函数，注入依赖
@RequiredArgsConstructor
// 实现 CarriageService 接口的类，
public class CarriageServiceIpml implements CarriageService {
    // 注入分布式缓存实例，用于操作 Redis
    private final DistributedCache distributedCache;
    // 注入 MyBatis-Plus 的 Mapper，用于数据库操作
    //carriage继承basemapper 用于对carriageDTO的操作
    private final CarriageMapper carriageMapper;
    // 注入 Redisson 客户端，用于分布式锁
    private final RedissonClient redissonClient;

    // 重写 CarriageService 接口中的方法

    /**
    * @Author: Jav
    * @Date: 2025/3/16
    * @Description: 列出车厢的编号，但是这个方法从来没有被使用过
    * @Param: [java.lang.String, java.lang.Integer]
    * @return: java.util.List<java.lang.String>
    *
    */
    @Override
    // 查询指定 trainId 和 carriageType 的车厢编号列表
    public List<String> listCarriageNumber(String trainId, Integer carriageType) {
        // 定义 Redis 缓存的 key，格式为 "TRAIN_CARRIAGE:trainId"
        final String key = TRAIN_CARRIAGE + trainId;
        // 调用安全获取车厢编号的方法
        return safeGetCarriageNumber(
                // 火车 ID
                trainId,
                // 缓存 key
                key,
                // 车厢类型
                carriageType,
                // Lambda 表达式，作为 CacheLoader 的实现，用于从数据库加载数据
                () -> {
                    // 创建 MyBatis-Plus 的查询条件构造器
                    LambdaQueryWrapper<CarriageDO> queryWrapper = Wrappers.lambdaQuery(CarriageDO.class)
                            // 条件：trainId 等于指定值
                            .eq(CarriageDO::getTrainId, trainId)
                            // 条件：carriageType 等于指定值
                            .eq(CarriageDO::getCarriageType, carriageType);
                    // 从数据库查询符合条件的车厢列表
                    List<CarriageDO> carriageDOList = carriageMapper.selectList(queryWrapper);
                    // 将查询结果转为流
                    // 提取每个 CarriageDO 的车厢编号
                    // 收集为 List<String>
                    List<String> carriageListWithOnlyNumber = carriageDOList.stream().map(CarriageDO::getCarriageNumber).collect(Collectors.toList());
                    // 将车厢编号用逗号拼接成字符串
                    return StrUtil.join(StrUtil.COMMA, carriageListWithOnlyNumber);
                });
    }



    // 安全获取车厢编号，带分布式锁保护
    private List<String> safeGetCarriageNumber(String trainId, final String key, Integer carriageType, CacheLoader<String> loader) {
        // 第一次尝试从缓存中获取车厢编号
        String result = getCarriageNumber(key, carriageType);
        // 如果缓存中有有效数据
        if (!CacheUtil.isNullOrBlank(result)) {
            // 将逗号分隔的字符串拆分为 List 返回
            return StrUtil.split(result, StrUtil.COMMA);
        }
        // 获取 Redisson 分布式锁，锁的 key 包含 trainId
        RLock lock = redissonClient.getLock(String.format(LOCK_QUERY_CARRIAGE_NUMBER_LIST, trainId));
        // 加锁，防止并发查询导致缓存击穿
        lock.lock();
        try {
            // 双重检查：再次从缓存获取，确认是否仍为空
            if (CacheUtil.isNullOrBlank(result = getCarriageNumber(key, carriageType))) {
                // 如果仍为空，加载数据并设置缓存
                if (CacheUtil.isNullOrBlank(result = loadAndSet(carriageType, key, loader))) {
                    // 如果加载结果为空，返回空列表
                    return Collections.emptyList();
                }
            }
        } finally {
            // 释放分布式锁，无论是否发生异常
            lock.unlock();
        }
        // 将最终结果拆分为 List 返回
        return StrUtil.split(result, StrUtil.COMMA);
    }

    // 从数据库加载数据并设置到缓存
    private String loadAndSet(Integer carriageType, final String key, CacheLoader<String> loader) {
        // 调用传入的 loader（Lambda 表达式）从数据库加载数据
        String result = loader.load();
        // 如果加载结果为空
        if (CacheUtil.isNullOrBlank(result)) {
            // 直接返回空结果，不设置缓存
            return result;
        }
        // 获取 Hash 操作对象
        HashOperations<String, Object, Object> hashOperations = getHashOperations();
        // 将结果存入 Redis Hash，仅当 key-field 不存在时写入
        hashOperations.putIfAbsent(key, String.valueOf(carriageType), result);
        // 返回加载结果
        return result;
    }


    // 从 Redis 缓存中获取车厢编号
    private String getCarriageNumber(final String key, Integer carriageType) {
        // 获取 Hash 操作对象
        HashOperations<String, Object, Object> hashOperations = getHashOperations();
        // 从 Redis Hash 中获取指定 key 和 field 的值
        // 将结果转换为字符串
        // 如果为空，返回空字符串
        return Optional.ofNullable(hashOperations.get(key, String.valueOf(carriageType))).map(Object::toString).orElse("");
    }
    // 获取 Redis Hash 操作对象的方法
    private HashOperations<String, Object, Object> getHashOperations() {
        // 从 DistributedCache 获取 StringRedisTemplate 实例
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 返回 Redis Hash 操作对象，用于操作 Hash 数据结构
        return stringRedisTemplate.opsForHash();
    }
}