package org.jav.train12306.cache;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.common.enums.SeatStatusEnum;
import org.jav.train12306.common.enums.VehicleTypeEnum;
import org.jav.train12306.dao.entity.SeatDO;
import org.jav.train12306.dao.entity.TrainDO;
import org.jav.train12306.dao.mapper.SeatMapper;
import org.jav.train12306.dao.mapper.TrainMapper;
import org.jav.train12306.dto.domain.RouteDTO;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.cache.toolkit.CacheUtil;
import org.jav.train12306.service.TrainStationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.jav.train12306.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.jav.train12306.common.constant.RedisKeyConstant.*;

/**
 * 座位余量缓存加载器
 * 核心功能：在缓存未命中时加载并缓存列车不同区间的座位余量
 */
@Component
@RequiredArgsConstructor  // Lombok自动生成构造函数注入依赖
public class SeatMarginCacheLoader {
    // 数据访问层依赖
    private final TrainMapper trainMapper;
    // 列车信息Mapper
    private final SeatMapper seatMapper;
    // 座位信息Mapper

    // 缓存相关依赖
    private final DistributedCache distributedCache;
    // 分布式缓存抽象
    private final RedissonClient redissonClient;
    // Redisson客户端

    // 服务层依赖
    private final TrainStationService trainStationService;
    // 车站路线服务

    /**
     * 核心方法：加载指定列车区间的座位余量
     * @param trainId 列车ID
     * @param seatType 座位类型
     * @param departure 出发站
     * @param arrival 到达站
     * @descrption
     * 缓存预热
     * 在系统启动时，加载并缓存列车的余量数据，减少后续查询的数据库压力。
     *
     *余量查询
     * 在用户查询余票时，直接从缓存中获取数据，提升查询性能。
     *
     * 数据兜底
     * 当路线信息不存在时，初始化默认余量数据，避免缓存为空导致查询失败
     * @return 座位余量映射表（格式：座位类型 -> 余量）
     */
    public Map<String, String> load(String trainId, String seatType, String departure, String arrival) {
        // 使用LinkedHashMap保持插入顺序，确保缓存顺序一致性
        Map<String, Map<String, String>> trainStationRemainingTicketMaps = new LinkedHashMap<>();

        // 构建缓存键后缀（格式：trainId_departure_arrival）
        String keySuffix = CacheUtil.buildKey(trainId, departure, arrival);

        // 获取分布式锁（防止缓存击穿）redissonLock String是锁的名字
        RLock lock = redissonClient.getLock(String.format(LOCK_SAFE_LOAD_SEAT_MARGIN_GET, keySuffix));
        lock.lock();  // 阻塞式获取锁
        try {
            // 获取Redis操作实例
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();

            // 尝试从缓存获取余票数量
            Object quantityObj = stringRedisTemplate.opsForHash().get(
                    TRAIN_STATION_REMAINING_TICKET + keySuffix,
                    seatType
            );

            // 缓存未命中时的处理逻辑
            if (CacheUtil.isNullOrBlank(quantityObj)) {
                // 从缓存或数据库获取列车基本信息
                TrainDO trainDO = distributedCache.safeGet(
                        TRAIN_INFO + trainId,
                        TrainDO.class,
                        // 缓存未命中时从DB加载  传递回调函数当作方法
                        () -> trainMapper.selectById(trainId),
                        ADVANCE_TICKET_DAY,
                        // 缓存有效期（15天）
                        TimeUnit.DAYS
                );

                // 获取列车完整运行路线信息
                List<RouteDTO> routeDTOList = trainStationService.listTrainStationRoute(
                        trainId,
                        trainDO.getStartStation(),
                        trainDO.getEndStation()
                );

                // 处理存在路线信息的场景
                if (CollUtil.isNotEmpty(routeDTOList)) {
                    // 根据列车类型初始化不同座位类型的余量
                    switch (trainDO.getTrainType()) {
                        case 0 -> {
                            // 普通列车类型
                            for (RouteDTO each : routeDTOList) {
                                Map<String, String> remainingTicket = new LinkedHashMap<>();
                                remainingTicket.put("0", selectSeatMargin(trainId, 0, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("1", selectSeatMargin(trainId, 1, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("2", selectSeatMargin(trainId, 2, each.getStartStation(), each.getEndStation()));
                                String actualKey = CacheUtil.buildKey(trainId, each.getStartStation(), each.getEndStation());
                                trainStationRemainingTicketMaps.put(TRAIN_STATION_REMAINING_TICKET + actualKey, remainingTicket);
                            }
                        }
                        case 1 -> {
                            // 高铁类型
                            for (RouteDTO each : routeDTOList) {
                                Map<String, String> remainingTicket = new LinkedHashMap<>();
                                remainingTicket.put("3", selectSeatMargin(trainId, 3, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("4", selectSeatMargin(trainId, 4, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("5", selectSeatMargin(trainId, 5, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("13", selectSeatMargin(trainId, 13, each.getStartStation(), each.getEndStation()));
                                String actualKey = CacheUtil.buildKey(trainId, each.getStartStation(), each.getEndStation());
                                trainStationRemainingTicketMaps.put(TRAIN_STATION_REMAINING_TICKET + actualKey, remainingTicket);
                            }
                        }
                        case 2 -> {
                            // 动车型
                            for (RouteDTO each : routeDTOList) {
                                Map<String, String> remainingTicket = new LinkedHashMap<>();
                                remainingTicket.put("6", selectSeatMargin(trainId, 6, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("7", selectSeatMargin(trainId, 7, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("8", selectSeatMargin(trainId, 8, each.getStartStation(), each.getEndStation()));
                                remainingTicket.put("13", selectSeatMargin(trainId, 13, each.getStartStation(), each.getEndStation()));
                                String actualKey = CacheUtil.buildKey(trainId, each.getStartStation(), each.getEndStation());
                                trainStationRemainingTicketMaps.put(TRAIN_STATION_REMAINING_TICKET + actualKey, remainingTicket);
                            }
                        }
                    }
                } else {  // 无路线信息时的兜底处理
                    Map<String, String> remainingTicket = new LinkedHashMap<>();
                    VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType())
                            .forEach(each -> remainingTicket.put(String.valueOf(each), "0"));
                    trainStationRemainingTicketMaps.put(TRAIN_STATION_REMAINING_TICKET + keySuffix, remainingTicket);
                }

                // 批量写入Redis（TODO: 可优化为LUA脚本保证原子性）
                trainStationRemainingTicketMaps.forEach((cacheKey, cacheMap) ->
                        stringRedisTemplate.opsForHash().putAll(cacheKey, cacheMap)
                );
            }
        } finally {
            lock.unlock();  // 确保锁释放
        }

        // 返回目标区间的余票数据
        return Optional.ofNullable(trainStationRemainingTicketMaps.get(TRAIN_STATION_REMAINING_TICKET + keySuffix))
                .orElse(new LinkedHashMap<>());
    }

    /**
     * 查询指定区间的可用座位数量
     * @param trainId 列车ID
     * @param type 座位类型
     * @param departure 出发站
     * @param arrival 到达站
     * @return 可用座位数量（字符串形式）
     */
    private String selectSeatMargin(String trainId, Integer type, String departure, String arrival) {
        // 构建MyBatis Plus查询条件
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, type)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival);

        // 执行查询并处理结果
        return Optional.ofNullable(seatMapper.selectCount(queryWrapper))
                .map(String::valueOf)  // 转换为字符串
                .orElse("0");          // 空值返回0
    }
}