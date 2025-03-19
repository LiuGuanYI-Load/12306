package org.jav.train12306.handler.ticket.tokenbucket;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jav.train12306.common.enums.VehicleTypeEnum;
import org.jav.train12306.dao.entity.TrainDO;
import org.jav.train12306.dao.mapper.TrainMapper;
import org.jav.train12306.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.jav.train12306.dto.domain.RouteDTO;
import org.jav.train12306.dto.domain.SeatTypeCountDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.remote.dto.TicketOrderDetailRespDTO;
import org.jav.train12306.framework.starter.bases.Singleton;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.common.toolkit.Assert;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.handler.ticket.dto.TokenResultDTO;
import org.jav.train12306.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.jav.train12306.service.SeatService;
import org.jav.train12306.service.TrainStationService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.jav.train12306.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.jav.train12306.common.constant.RedisKeyConstant.*;

@Slf4j
@Component // 声明为Spring组件，自动注入依赖
@RequiredArgsConstructor // Lombok注解，生成带所有final字段的构造函数
public final class TicketAvailabilityTokenBucket {

    // 注入列车站服务，用于获取站间路由
    private final TrainStationService trainStationService;
    // 注入分布式缓存（Redis），用于存储和操作余票数据
    private final DistributedCache distributedCache;
    // 注入Redisson客户端，用于分布式锁
    private final RedissonClient redissonClient;
    // 注入座位服务，用于查询座位计数
    private final SeatService seatService;
    // 注入列车映射器，用于数据库查询
    private final TrainMapper trainMapper;

    // 定义Lua脚本路径，用于检查和扣减余票
    private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH = "lua/ticket_availability_token_bucket.lua";
    // 定义Lua脚本路径，用于回滚余票
    private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH = "lua/ticket_availability_rollback_token_bucket.lua";

    /**
     * 获取车站间令牌桶中的令牌访问
     * 如果返回 true 表示有足够余票，可以继续购票流程
     * 如果返回 false 表示余票不足，无法购票
     *
     * @param requestParam 购票请求参数
     * @return 是否成功获取令牌的结果
     */
    public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
        // 从缓存安全获取列车信息，若无则从数据库查询，缓存有效期为预售天数
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(), // 缓存键：列车信息+列车ID
                TrainDO.class, // 返回类型
                () -> trainMapper.selectById(requestParam.getTrainId()), // 未命中时从数据库查询
                ADVANCE_TICKET_DAY, // 缓存过期时间
                TimeUnit.DAYS // 时间单位
        );

        // 获取列车从起点到终点的站间路由列表
        List<RouteDTO> routeDTOList = trainStationService
                .listTrainStationRoute(requestParam.getTrainId(), trainDO.getStartStation(), trainDO.getEndStation());

        // 获取Redis操作实例
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();

        // 构造令牌桶的Redis哈希键
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();

        // 检查令牌桶是否存在
        Boolean hasKey = distributedCache.hasKey(tokenBucketHashKey);

        // 如果令牌桶不存在，初始化余票数据
        if (!hasKey) {
            // 获取分布式锁，确保初始化过程线程安全
            RLock lock = redissonClient.getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
            if (!lock.tryLock()) { // 尝试加锁，若失败抛异常
                throw new ServiceException("购票异常，请稍候再试");
            }
            try {
                // 再次检查是否存在，避免重复初始化
                Boolean hasKeyTwo = distributedCache.hasKey(tokenBucketHashKey);
                if (!hasKeyTwo) {
                    // 根据列车类型获取支持的座位类型
                    List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
                    // 初始化余票映射表
                    Map<String, String> ticketAvailabilityTokenMap = new HashMap<>();
                    // 遍历站间路由
                    for (RouteDTO each : routeDTOList) {
                        // 查询每个站间路由的座位类型计数
                        List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(
                                Long.parseLong(requestParam.getTrainId()),
                                each.getStartStation(),
                                each.getEndStation(),
                                seatTypes
                        );
                        // 填充余票映射表，键为“起始站_终点站_座位类型”，值为余票数
                        for (SeatTypeCountDTO eachSeatTypeCountDTO : seatTypeCountDTOList) {
                            //令牌桶放的是什么？
                            //
                            //  hash 结构的key 是TICKET_AVAILABILITY_TOKEN_BUCKET 拼接上列车id   对应的map -->  map- key 起始站 终点站 座位类型   map-value 座位数量
                            String buildCacheKey = StrUtil.join("_", each.getStartStation(), each.getEndStation(), eachSeatTypeCountDTO.getSeatType());
                            ticketAvailabilityTokenMap.put(buildCacheKey, String.valueOf(eachSeatTypeCountDTO.getSeatCount()));
                        }
                    }
                    // 将余票数据存入Redis哈希表
                    // 构建关于列车id的令牌桶 就是一个hash 放一个key 对应一个map
                    stringRedisTemplate.opsForHash().putAll(TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId(), ticketAvailabilityTokenMap);
                }
            } finally {
                // 释放分布式锁
                lock.unlock();
            }
        }

        // 单例模式获取Lua脚本实例，避免重复创建
        DefaultRedisScript<String> actual = Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(String.class);
            return redisScript;
        });
        Assert.notNull(actual);
        // 确保脚本不为空

        // 统计乘客请求的座位类型和数量
        Map<Integer, Long> seatTypeCountMap = requestParam.getPassengers().stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));

        // 将座位类型和数量转换为JSON数组
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));

        // 获取用户选择的出发站到到达站的途经路由
        List<RouteDTO> takeoutRouteDTOList = trainStationService
                .listTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());

        // 构造Lua脚本的路由键（出发站_到达站）
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());

        // 执行Lua脚本，检查并扣减令牌桶里面的令牌
        // 参数包括：令牌桶键、路由键、座位需求JSON、途经路由JSON
        String resultStr = stringRedisTemplate.execute(actual, Lists.newArrayList(tokenBucketHashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));

        // 解析脚本返回结果
        TokenResultDTO result = JSON.parseObject(resultStr, TokenResultDTO.class);

        // 若结果为空，返回无票状态；否则返回脚本结果
        return result == null
                ? TokenResultDTO.builder().tokenIsNull(Boolean.TRUE).build()
                : result;
    }

    /**
     * 回滚列车余量令牌，一般在订单取消或长时间未支付时触发
     *
     * @param requestParam 订单详情，用于回滚余票
     */
    public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
        // 单例模式获取回滚Lua脚本实例
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual); // 确保脚本不为空

        // 获取订单中的乘客详情
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();

        // 统计需要回滚的座位类型和数量
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));

        // 转换为JSON数组
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet().stream()
                .map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", String.valueOf(entry.getKey()));
                    jsonObject.put("count", String.valueOf(entry.getValue()));
                    return jsonObject;
                })
                .collect(Collectors.toCollection(JSONArray::new));

        // 获取Redis操作实例
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();

        // 构造令牌桶的Redis哈希键
        String actualHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();

        // 构造路由键
        String luaScriptKey = StrUtil.join("_", requestParam.getDeparture(), requestParam.getArrival());

        // 获取途经站路由
        List<RouteDTO> takeoutRouteDTOList = trainStationService.listTrainStationRoute(String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());

        // 执行回滚Lua脚本，恢复余票
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(actualHashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutRouteDTOList));

        // 检查回滚结果，若失败则记录日志并抛异常
        if (result == null || !Objects.equals(result, 0L)) {
            log.error("回滚列车余票令牌失败，订单信息：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("回滚列车余票令牌失败");
        }
    }

    /**
     * 删除令牌桶，一般在令牌与数据库不一致时触发
     *
     * @param requestParam 购票请求参数
     */
    public void delTokenInBucket(PurchaseTicketReqDTO requestParam) {
        // 获取Redis操作实例
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 构造令牌桶键
        String tokenBucketHashKey = TICKET_AVAILABILITY_TOKEN_BUCKET + requestParam.getTrainId();
        // 删除令牌桶数据
        stringRedisTemplate.delete(tokenBucketHashKey);
    }

/*    // 未实现的方法，可能用于手动填充令牌
    public void putTokenInBucket() {
    }

    // 未实现的方法，可能用于初始化令牌
    public void initializeTokens() {
    }*/
}