package org.jav.train12306.handler.ticket.filter.purchase;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.common.constant.Index12306Constant;
import org.jav.train12306.dao.entity.TrainDO;
import org.jav.train12306.dao.entity.TrainStationDO;
import org.jav.train12306.dao.mapper.TrainMapper;
import org.jav.train12306.dao.mapper.TrainStationMapper;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.common.toolkit.EnvironmentUtil;
import org.jav.train12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.jav.train12306.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_INFO;
import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_STATION_STOPOVER_DETAIL;

@RequiredArgsConstructor
@Component
public class TrainPurchaseParamVerifyChainHandler implements TrainPurchaseTicketChainHandler<PurchaseTicketReqDTO>{
    private final DistributedCache distributedCache;
    private final TrainMapper trainMapper;
    private final TrainStationMapper trainStationMapper;
    /**
    * @Author: Jav
    * @Date: 2025/3/15
    * @Description:
      **key**: Redis 键。
      **clazz**: 返回的数据类型。
      **cacheLoader**: 传入的回调函数  当缓存数据不存在的时候 调用这个方法
      **timeout**: 缓存超时时间。
      **timeUnit**: 缓存超时时间单位。
      **bloomFilter**: 布隆过滤器。
      **cacheGetFilter**: 缓存键过滤器。
      **cacheGetIfAbsent**: 缓存不存在时的回调函数。
     * TODO 删除注释
     * 安全获取的函数
     *     @Override
     *     public <T> T safeGet(String key, Class<T> clazz, CacheLoader<T> cacheLoader, long timeout, TimeUnit timeUnit,
     *                          RBloomFilter<String> bloomFilter, CacheGetFilter<String> cacheGetFilter, CacheGetIfAbsent<String> cacheGetIfAbsent) {
     *         T result = get(key, clazz);
     *         // 缓存结果不等于空或空字符串直接返回；通过函数判断是否返回空，为了适配布隆过滤器无法删除的场景；两者都不成立，判断布隆过滤器是否存在，不存在返回空
     *         if (!CacheUtil.isNullOrBlank(result) //--> blank --"" or null
     *                 || Optional.ofNullable(cacheGetFilter).map(each -> each.filter(key)).orElse(false)
     *                 || Optional.ofNullable(bloomFilter).map(each -> !each.contains(key)).orElse(false)) {
     *             return result;
     *         }
     *         RLock lock = redissonClient.getLock(SAFE_GET_DISTRIBUTED_LOCK_KEY_PREFIX + key);
     *         lock.lock();
     *         try {
     *             // 双重判定锁，减轻获得分布式锁后线程访问数据库压力
     *             if (CacheUtil.isNullOrBlank(result = get(key, clazz))) {
     *                 // 如果访问 cacheLoader 加载数据为空，执行后置函数操作
     *                 if (CacheUtil.isNullOrBlank(result = loadAndSet(key, cacheLoader, timeout, timeUnit, true, bloomFilter))) {
     *                     Optional.ofNullable(cacheGetIfAbsent).ifPresent(each -> each.execute(key));
     *                 }
     *             }
     *         } finally {
     *             lock.unlock();
     *         }
     *         return result;
     *     }
     *     TODO 删除注释
     *     get函数
     *
     *     public <T> T get(String key, Class<T> clazz) {
     *         String value = stringRedisTemplate.opsForValue().get(key);
     *         if (String.class.isAssignableFrom(clazz)) {
     *             return (T) value;
     *         }
     *         return JSON.parseObject(value, FastJson2Util.buildType(clazz));
     *     }
     * @Param: [org.jav.train12306.dto.req.PurchaseTicketReqDTO]
    * @return: void
    *
    */
    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        TrainDO trainDO=distributedCache.safeGet(TRAIN_INFO + requestParam.getTrainId(),TrainDO.class, () -> trainMapper.selectById(requestParam.getTrainId()),ADVANCE_TICKET_DAY,
                TimeUnit.DAYS);
        if(Objects.isNull(trainDO)){
            // 如果按照严谨逻辑，类似异常应该记录当前用户的 userid 并发送到风控中心
            // 如果一段时间有过几次的异常，直接封号处理。下述异常同理
            /*某些用户可能会通过重复尝试非法操作（如频繁查询不存在的车次）来攻击系统，导致资源浪费或系统崩溃。
            封号可以阻止这些恶意用户继续操作。*/
            throw new ClientException("查询时发生错误！，您所查找的车次不存在");
        }
        // TODO，当前列车数据并没有通过定时任务每天生成最新的，所以需要隔离这个拦截。后期定时生成数据后删除该判断
        if (!EnvironmentUtil.isDevEnvironment()) {
            // 查询车次是否已经发售
            if (new Date().before(trainDO.getSaleTime())) {
                throw new ClientException("列车车次暂未发售");
            }
            // 查询车次是否在有效期内
            if (new Date().after(trainDO.getDepartureTime())) {
                throw new ClientException("列车车次已出发禁止购票");
            }
        }
        // 车站是否存在车次中，以及车站的顺序是否正确
        //获取到缓存 然后解析到数组
        String trainStationStopoverDetailStr = distributedCache.safeGet(
                TRAIN_STATION_STOPOVER_DETAIL + requestParam.getTrainId(),
                String.class,
                () -> {
                    LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                            .eq(TrainStationDO::getTrainId, requestParam.getTrainId())
                            .select(TrainStationDO::getDeparture);
                    List<TrainStationDO> actualTrainStationList = trainStationMapper.selectList(queryWrapper);
                    return CollUtil.isNotEmpty(actualTrainStationList) ? JSON.toJSONString(actualTrainStationList) : null;
                },
                Index12306Constant.ADVANCE_TICKET_DAY,
                TimeUnit.DAYS
        );
        //解析
        List<TrainStationDO> trainDOList = JSON.parseArray(trainStationStopoverDetailStr, TrainStationDO.class);
        //判断参数是否有效
        boolean validateStation = validateStation(
                trainDOList.stream().map(TrainStationDO::getDeparture).toList(),
                requestParam.getDeparture(),
                requestParam.getArrival()
        );
        if (!validateStation) {
            throw new ClientException("列车车站数据错误");
        }
    }
    @Override
    public int getOrder() {
        return 10;
    }

    public boolean validateStation(List<String> stationList, String startStation, String endStation) {
        int index1 = stationList.indexOf(startStation);
        int index2 = stationList.indexOf(endStation);
        if (index1 == -1 || index2 == -1) {
            return false;
        }
        return index2 >= index1;
    }
}
