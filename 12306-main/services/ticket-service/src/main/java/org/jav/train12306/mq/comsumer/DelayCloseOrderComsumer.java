package org.jav.train12306.mq.comsumer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.jav.train12306.common.constant.TicketRocketMQConstant;
import org.jav.train12306.dto.domain.RouteDTO;
import org.jav.train12306.dto.req.CancelTicketOrderReqDTO;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.common.toolkit.BeanUtil;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.framework.starter.idempotent.annotation.Idempotent;
import org.jav.train12306.framework.starter.idempotent.enums.IdempotentSceneEnum;
import org.jav.train12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.jav.train12306.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.jav.train12306.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.jav.train12306.mq.domain.MessageWrapper;
import org.jav.train12306.mq.event.DelayCloseOrderEvent;
import org.jav.train12306.remote.TicketOrderRemoteService;
import org.jav.train12306.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.jav.train12306.service.SeatService;
import org.jav.train12306.service.TrainStationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.jav.train12306.remote.dto.TicketOrderDetailRespDTO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;

/**
 * 延迟关闭订单消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = TicketRocketMQConstant.ORDER_DELAY_CLOSE_TOPIC_KEY,
        selectorExpression = TicketRocketMQConstant.ORDER_DELAY_CLOSE_TAG_KEY,
        consumerGroup = TicketRocketMQConstant.TICKET_DELAY_CLOSE_CG_KEY
)
public class DelayCloseOrderComsumer implements RocketMQListener<MessageWrapper<DelayCloseOrderEvent>> {
    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;

    private final SeatService seatService;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;

    @Idempotent(
            uniqueKeyPrefix = "index12306-ticket:delay_close_order:",
            key = "#delayCloseOrderEventMessageWrapper.getKeys()+'_'+#delayCloseOrderEventMessageWrapper.hashCode()",
            type = IdempotentTypeEnum.SPEL,
            scene = IdempotentSceneEnum.MQ,
            keyTimeout = 7200L
    )
    /**
     *    先回滚缓存，再回滚令牌桶
     *    /*
     *             回滚顺序相反的可能问题
     *             (1) 缓存未更新，令牌桶已更新**
     *             如果先回滚令牌桶，令牌桶中的令牌数量会增加，表示有更多的座位可用。
     *             但缓存中的余票数量尚未更新，用户查询到的余票数量仍然是旧的。
     *             此时，系统允许用户购票，但实际上没有足够的座位，导致购票失败。
     *             (2) 缓存更新失败，令牌桶已更新**
     *             如果先回滚令牌桶，令牌桶中的令牌数量会增加。
     *             但在回滚缓存时，如果 Redis 操作失败（如网络问题或 Redis 宕机），缓存中的余票数量不会更新。
     *             此时，令牌桶中的令牌数量与缓存中的余票数量不一致，导致系统无法正常运行
     *             (3) 并发问题
     *             在高并发场景下，如果先回滚令牌桶，其他线程可能会在缓存更新之前读取到旧的余票信息，导致数据不一致。
     *
     */
    @Override
    public void onMessage(MessageWrapper<DelayCloseOrderEvent> delayCloseOrderEventMessageWrapper) {
        log.info("[延迟关闭订单] 开始消费：{}", JSON.toJSONString(delayCloseOrderEventMessageWrapper));
        //这个DelayCloseOrderEvent
        DelayCloseOrderEvent delayCloseOrderEvent = delayCloseOrderEventMessageWrapper.getMessage();
        String orderSn=delayCloseOrderEvent.getOrderSn();
        Result<Boolean> closedTickOrder;
        try{
            //关闭订单
            closedTickOrder = ticketOrderRemoteService.closeTickOrder(new CancelTicketOrderReqDTO(orderSn));
        }catch(Throwable ex){
            log.info("延迟关闭订单]订单号：{}远程调用订单服务失败",orderSn,ex);
            //抛出异常
            throw ex;
        }
        //未开启binlog的执行判断
        if(closedTickOrder.isSuccess()&&!StrUtil.equals(ticketAvailabilityCacheUpdateType, "binlog")){
            if(!closedTickOrder.getData()){
                log.info("[延迟关闭订单] 订单号：{} 用户已支付订单", orderSn);
                return;
            }
        }
        //得到经典 三件套 列车id  start 和 end
        String trainId = delayCloseOrderEvent.getTrainId();
        String departure = delayCloseOrderEvent.getDeparture();
        String arrival = delayCloseOrderEvent.getArrival();
        //得到购买票的结果尝试 回滚
        // getTrainPurchaseTicketResults()  ---->  乘车人购票信息
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults = delayCloseOrderEvent.getTrainPurchaseTicketResults();
        try {
            //锁定了座位但是并没有支付  延迟订单的消息 现在要把座位的锁定状态 解锁
            seatService.unlock(trainId, departure, arrival, trainPurchaseTicketResults);
        } catch (Throwable ex) {
            log.error("[延迟关闭订单] 订单号：{} 回滚列车DB座位状态失败", orderSn, ex);
            throw ex;
        }
        try {
            //解锁失败
            //回滚 按照座位类型分类 票张数
            StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            Map<Integer, List<TrainPurchaseTicketRespDTO>> seatTypeMap = trainPurchaseTicketResults.stream()
                    .collect(Collectors.groupingBy(TrainPurchaseTicketRespDTO::getSeatType));
            //得到路线 集合
            List<RouteDTO> routeDTOList = trainStationService.listTrainStationRoute(trainId, departure, arrival);
            routeDTOList.forEach(each -> {
                String keySuffix = StrUtil.join("_", trainId, each.getStartStation(), each.getEndStation());
                seatTypeMap.forEach((seatType, trainPurchaseTicketRespDTOList) -> {
                    stringRedisTemplate.opsForHash()
                            //执行缓存张数的增加 增加的数量对应 该类型的被买票的数量的多少  也就是 链表的大小size()
                            .increment(TRAIN_STATION_REMAINING_TICKET + keySuffix, String.valueOf(seatType), trainPurchaseTicketRespDTOList.size());
                });
            });
            TicketOrderDetailRespDTO ticketOrderDetail = BeanUtil.convert(delayCloseOrderEvent, TicketOrderDetailRespDTO.class);
            ticketOrderDetail.setPassengerDetails(BeanUtil.convert(delayCloseOrderEvent.getTrainPurchaseTicketResults(), TicketOrderPassengerDetailRespDTO.class));
            //回滚缓存之后 再回滚令牌桶
            ticketAvailabilityTokenBucket.rollbackInBucket(ticketOrderDetail);
        } catch (Throwable ex) {
            log.error("[延迟关闭订单] 订单号：{} 回滚列车Cache余票失败", orderSn, ex);
            throw ex;
        }
    }
}
