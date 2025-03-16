package org.jav.train12306.service.impl;/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jav.train12306.common.enums.SourceEnum;
import org.jav.train12306.common.enums.TicketChainMarkEnum;
import org.jav.train12306.common.enums.TicketStatusEnum;
import org.jav.train12306.common.enums.VehicleTypeEnum;
import org.jav.train12306.dao.entity.TicketDO;
import org.jav.train12306.dao.entity.TrainDO;
import org.jav.train12306.dao.entity.TrainStationPriceDO;
import org.jav.train12306.dao.entity.TrainStationRelationDO;
import org.jav.train12306.dao.mapper.*;
import org.jav.train12306.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.jav.train12306.dto.domain.SeatClassDTO;
import org.jav.train12306.dto.domain.SeatTypeCountDTO;
import org.jav.train12306.dto.domain.TicketListDTO;
import org.jav.train12306.dto.req.CancelTicketOrderReqDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.dto.req.RefundTicketReqDTO;
import org.jav.train12306.dto.req.TicketPageQueryReqDTO;
import org.jav.train12306.dto.resp.RefundTicketRespDTO;
import org.jav.train12306.dto.resp.TicketOrderDetailRespDTO;
import org.jav.train12306.dto.resp.TicketPageQueryRespDTO;
import org.jav.train12306.dto.resp.TicketPurchaseRespDTO;
import org.jav.train12306.framework.starter.bases.ApplicationContextHolder;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.jav.train12306.framework.starter.idempotent.annotation.Idempotent;
import org.jav.train12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.jav.train12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.jav.train12306.frameworks.starter.user.core.UserContext;
import org.jav.train12306.handler.ticket.dto.TokenResultDTO;
import org.jav.train12306.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.jav.train12306.handler.ticket.selector.TrainSeatTypeSelector;
import org.jav.train12306.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.jav.train12306.remote.PayRemoteService;
import org.jav.train12306.remote.TicketOrderRemoteService;
import org.jav.train12306.remote.dto.PayInfoRespDTO;
import org.jav.train12306.remote.dto.TicketOrderCreateRemoteReqDTO;
import org.jav.train12306.remote.dto.TicketOrderItemCreateRemoteReqDTO;
import org.jav.train12306.service.SeatService;
import org.jav.train12306.service.TicketService;
import org.jav.train12306.toolkit.TimeStringComparator;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.jav.train12306.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.jav.train12306.common.constant.RedisKeyConstant.*;


/**
 * 车票接口实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService, CommandLineRunner {

    private final TrainMapper trainMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final DistributedCache distributedCache;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final PayRemoteService payRemoteService;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    private final StationMapper stationMapper;
    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryAbstractChainContext;
    private final AbstractChainContext<PurchaseTicketReqDTO> purchaseTicketAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final RedissonClient redissonClient;
    private final ConfigurableEnvironment environment;
    private TicketService ticketService;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final SeatService seatService;
    private final Cache<String, ReentrantLock> localLockMap = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    private final Cache<String, Object> tokenTicketsRefreshMap = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;
    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;


    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        //责任链
        ticketPageQueryAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        //得到对缓存操作的实例  注意这里的缓存不是令牌容器
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each -> connection.stringCommands().get(each.getBytes()));
            return null;
        });
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        for (Object each : trainStationPriceObjs) {
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            trainStationPriceDOList.addAll(trainStationPriceList);
            for (TrainStationPriceDO item : trainStationPriceList) {
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        List<Object> trainStationRemainingObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            for (int i = 0; i < trainStationRemainingKeyList.size(); i++) {
                connection.hashCommands().hGet(trainStationRemainingKeyList.get(i).getBytes(), trainStationPriceDOList.get(i).getSeatType().toString().getBytes());
            }
            return null;
        });
        for (TicketListDTO each : seatResults) {
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString()))
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP))
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }
    /**
    * @Author: Jav
    * @Date: 2025/3/15
    * @Description:  买票核心代码
    * @Param: [org.jav.train12306.dto.req.PurchaseTicketReqDTO]
    * @return: org.jav.train12306.dto.resp.TicketPurchaseRespDTO
    *
    */
    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:lock_purchase-tickets:",
            key = "T(org.opengoofy.index12306.framework.starter.bases.ApplicationContextHolder).getBean('environment').getProperty('unique-name', '')"
                    + "+'_'+"
                    + "T(org.opengoofy.index12306.frameworks.starter.user.core.UserContext).getUsername()",
            message = "正在执行下单流程，请稍后...",
            scene = IdempotentSceneEnum.RESTAPI,
            type = IdempotentTypeEnum.SPEL
    )
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        // 使用责任链模式验证请求参数，包括必填项、正确性和乘客是否已购票
        purchaseTicketAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);

        // 从令牌桶中获取令牌，检查是否有余票可用
        TokenResultDTO tokenResult = ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);
        // 如果令牌为空（无余票）
        if (tokenResult.getTokenIsNull()) {
            // 从本地缓存中检查是否已为该列车刷新过令牌
            Object ifPresentObj = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
            // 如果缓存中没有刷新记录
            if (ifPresentObj == null) {
                // 使用 synchronized 块同步，确保只有一个线程执行刷新逻辑
                synchronized (TicketService.class) {
                    // 再次检查缓存，避免重复刷新
                    if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId()) == null) {
                        // 创建一个占位对象，表示正在刷新
                        ifPresentObj = new Object();
                        // 将占位对象放入缓存，有效期10分钟
                        tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
                        // 异步刷新令牌桶，尝试更新余票状态
                        tokenIsNullRefreshToken(requestParam, tokenResult);
                    }
                }
            }
            // 如果仍无余票，抛出异常终止流程
            throw new ServiceException("列车站点已无余票");
        }

        // 初始化本地锁列表，用于单机并发控制
        List<ReentrantLock> localLockList = new ArrayList<>();
        // 初始化分布式锁列表，用于跨节点并发控制
        List<RLock> distributedLockList = new ArrayList<>();
        // 将乘客按座位类型分组，便于为每种座位类型加锁
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        // 遍历每种座位类型，创建对应的锁
        seatTypeMap.forEach((seatType, count) -> {
            // 构造锁的键名，包含列车ID和座位类型
            String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getTrainId(), seatType));
            // 从本地锁缓存中获取锁对象
            ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
            // 如果本地锁不存在
            if (localLock == null) {
                // 使用 synchronized 块同步，确保锁只创建一次
                synchronized (TicketService.class) {
                    // 再次检查缓存，避免重复创建
                    if ((localLock = localLockMap.getIfPresent(lockKey)) == null) {
                        // 创建公平的可重入锁
                        localLock = new ReentrantLock(true);
                        // 将锁放入本地缓存，有效期1天
                        localLockMap.put(lockKey, localLock);
                    }
                }
            }
            // 将本地锁添加到列表
            localLockList.add(localLock);
            // 创建分布式公平锁，用于跨节点同步
            RLock distributedLock = redissonClient.getFairLock(lockKey);
            // 将分布式锁添加到列表
            distributedLockList.add(distributedLock);
        });

        // 使用 try-finally 块确保锁被正确释放
        try {
            // 对所有本地锁加锁，控制单机并发
            localLockList.forEach(ReentrantLock::lock);
            // 对所有分布式锁加锁，控制分布式并发
            distributedLockList.forEach(RLock::lock);
            // 执行购票核心逻辑，返回购票结果
            return ticketService.executePurchaseTickets(requestParam);
        } finally {
            // 释放所有本地锁，忽略异常以避免影响主流程
            localLockList.forEach(localLock -> {
                try {
                    localLock.unlock();
                } catch (Throwable ignored) {
                }
            });
            // 释放所有分布式锁，忽略异常以避免影响主流程
            distributedLockList.forEach(distributedLock -> {
                try {
                    distributedLock.unlock();
                } catch (Throwable ignored) {
                }
            });
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class) // 开启事务，异常时回滚
    public TicketPurchaseRespDTO executePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        // 初始化订单详情结果列表
        List<TicketOrderDetailRespDTO> ticketOrderDetailResults = new ArrayList<>();

        // 获取请求中的列车ID
        String trainId = requestParam.getTrainId();

        // 从分布式缓存中获取列车信息，如果缓存中没有则从数据库查询并缓存
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId, // 缓存键：TRAIN_INFO + trainId
                TrainDO.class, // 缓存值类型
                () -> trainMapper.selectById(trainId), // 缓存未命中时的数据加载逻辑
                ADVANCE_TICKET_DAY, // 缓存过期时间
                TimeUnit.DAYS // 时间单位
        );

        // 根据列车类型和购票请求选择座位类型和购票结果
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = trainSeatTypeSelector.select(trainDO.getTrainType(), requestParam);

        // 将购票结果转换为 TicketDO 实体列表
        List<TicketDO> ticketDOList = trainPurchaseTicketResults.stream()
                .map(each -> TicketDO.builder() // 构建 TicketDO 实体
                        .username(UserContext.getUsername()) // 设置用户名
                        .trainId(Long.parseLong(requestParam.getTrainId())) // 设置列车ID
                        .carriageNumber(each.getCarriageNumber()) // 设置车厢号
                        .seatNumber(each.getSeatNumber()) // 设置座位号
                        .passengerId(each.getPassengerId()) // 设置乘客ID
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode()) // 设置票状态为未支付
                        .build()
                )
                .toList(); // 转换为列表

        // 批量保存 TicketDO 实体到数据库
        saveBatch(ticketDOList);

        // 初始化订单服务调用结果
        Result<String> ticketOrderResult;

        try {
            // 初始化订单项创建请求列表
            List<TicketOrderItemCreateRemoteReqDTO> orderItemCreateRemoteReqDTOList = new ArrayList<>();

            // 遍历购票结果，构建订单项请求和订单详情响应
            trainPurchaseTicketResults.forEach(each -> {
                // 构建订单项创建请求
                TicketOrderItemCreateRemoteReqDTO orderItemCreateRemoteReqDTO = TicketOrderItemCreateRemoteReqDTO.builder()
                        .amount(each.getAmount()) // 设置金额
                        .carriageNumber(each.getCarriageNumber()) // 设置车厢号
                        .seatNumber(each.getSeatNumber()) // 设置座位号
                        .idCard(each.getIdCard()) // 设置身份证号
                        .idType(each.getIdType()) // 设置证件类型
                        .phone(each.getPhone()) // 设置手机号
                        .seatType(each.getSeatType()) // 设置座位类型
                        .ticketType(each.getUserType()) // 设置票类型
                        .realName(each.getRealName()) // 设置真实姓名
                        .build();

                // 构建订单详情响应
                TicketOrderDetailRespDTO ticketOrderDetailRespDTO = TicketOrderDetailRespDTO.builder()
                        .amount(each.getAmount()) // 设置金额
                        .carriageNumber(each.getCarriageNumber()) // 设置车厢号
                        .seatNumber(each.getSeatNumber()) // 设置座位号
                        .idCard(each.getIdCard()) // 设置身份证号
                        .idType(each.getIdType()) // 设置证件类型
                        .seatType(each.getSeatType()) // 设置座位类型
                        .ticketType(each.getUserType()) // 设置票类型
                        .realName(each.getRealName()) // 设置真实姓名
                        .build();

                // 将订单项请求添加到列表
                orderItemCreateRemoteReqDTOList.add(orderItemCreateRemoteReqDTO);

                // 将订单详情响应添加到结果列表
                ticketOrderDetailResults.add(ticketOrderDetailRespDTO);
            });

            // 查询列车站点关系信息
            LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                    .eq(TrainStationRelationDO::getTrainId, trainId) // 列车ID条件
                    .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture()) // 出发站条件
                    .eq(TrainStationRelationDO::getArrival, requestParam.getArrival()); // 到达站条件

            // 执行查询，获取列车站点关系信息
            TrainStationRelationDO trainStationRelationDO = trainStationRelationMapper.selectOne(queryWrapper);

            // 构建订单创建请求
            TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                    .departure(requestParam.getDeparture()) // 设置出发站
                    .arrival(requestParam.getArrival()) // 设置到达站
                    .orderTime(new Date()) // 设置订单时间
                    .source(SourceEnum.INTERNET.getCode()) // 设置订单来源为互联网
                    .trainNumber(trainDO.getTrainNumber()) // 设置列车号
                    .departureTime(trainStationRelationDO.getDepartureTime()) // 设置出发时间
                    .arrivalTime(trainStationRelationDO.getArrivalTime()) // 设置到达时间
                    .ridingDate(trainStationRelationDO.getDepartureTime()) // 设置乘车日期
                    .userId(UserContext.getUserId()) // 设置用户ID
                    .username(UserContext.getUsername()) // 设置用户名
                    .trainId(Long.parseLong(requestParam.getTrainId())) // 设置列车ID
                    .ticketOrderItems(orderItemCreateRemoteReqDTOList) // 设置订单项列表
                    .build();

            // 远程调用订单服务创建订单
            ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);

            // 校验订单服务调用结果
            if (!ticketOrderResult.isSuccess() || StrUtil.isBlank(ticketOrderResult.getData())) {
                log.error("订单服务调用失败，返回结果：{}", ticketOrderResult.getMessage());
                throw new ServiceException("订单服务调用失败");
            }
        } catch (Throwable ex) {
            // 捕获异常并记录日志
            log.error("远程调用订单服务创建错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex; // 抛出异常
        }

        // 返回购票响应结果，包含订单号和订单详情
        return new TicketPurchaseRespDTO(ticketOrderResult.getData(), ticketOrderDetailResults);
    }

    @Override
    public PayInfoRespDTO getPayInfo(String orderSn) {
        return null;
    }

    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {

    }

    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        return null;
    }


    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).distinct().collect(Collectors.toList());
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).distinct().collect(Collectors.toList());
    }

    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> resultSeatClassList = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            for (SeatClassDTO item : each.getSeatClassList()) {
                resultSeatClassList.add(item.getType());
            }
        }
        return resultSeatClassList.stream().toList();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> trainBrandSet = new HashSet<>();
        for (TicketListDTO each : seatResults) {
            if (StrUtil.isNotBlank(each.getTrainBrand())) {
                trainBrandSet.addAll(StrUtil.split(each.getTrainBrand(), ",").stream().map(Integer::parseInt).toList());
            }
        }
        return trainBrandSet.stream().toList();
    }

    private final ScheduledExecutorService tokenIsNullRefreshExecutor = Executors.newScheduledThreadPool(1);

    private void tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResult) {
        RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL, requestParam.getTrainId()));
        if (!lock.tryLock()) {
            return;
        }
        tokenIsNullRefreshExecutor.schedule(() -> {
            try {
                List<Integer> seatTypes = new ArrayList<>();
                Map<Integer, Integer> tokenCountMap = new HashMap<>();
                tokenResult.getTokenIsNullSeatTypeCounts().stream()
                        .map(each -> each.split("_"))
                        .forEach(split -> {
                            int seatType = Integer.parseInt(split[0]);
                            seatTypes.add(seatType);
                            tokenCountMap.put(seatType, Integer.parseInt(split[1]));
                        });
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.parseLong(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);
                for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                    Integer tokenCount = tokenCountMap.get(each.getSeatType());
                    if (tokenCount <= each.getSeatCount()) {
                        ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                        break;
                    }
                }
            } finally {
                lock.unlock();
            }
        }, 10, TimeUnit.SECONDS);
    }

    @Override
    public void run(String... args) throws Exception {
        ticketService = ApplicationContextHolder.getBean(TicketService.class);
    }
}
