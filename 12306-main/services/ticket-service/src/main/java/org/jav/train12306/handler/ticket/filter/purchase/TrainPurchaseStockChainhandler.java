package org.jav.train12306.handler.ticket.filter.purchase;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.cache.SeatMarginCacheLoader;
import org.jav.train12306.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.convention.exception.ClientException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_STATION_REMAINING_TICKET;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
@RequiredArgsConstructor
@Component
public class TrainPurchaseStockChainhandler implements TrainPurchaseTicketChainHandler<PurchaseTicketReqDTO>{
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final DistributedCache distributedCache;

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        // 车次站点是否还有余票。如果用户提交多个乘车人非同一座位类型，拆分验证
        //使用strUtl构建key
        String keySuffix = StrUtil.join("_", requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        //获取实例来操作
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        //乘车人集合
        List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();

        // 按座位类型对乘客进行分组（如商务座/一等座/二等座）[4,5](@ref)
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));

        // 遍历每个座位类型的分组进行余票检查
        seatTypeMap.forEach((seatType, passengerSeatDetails) -> {
            // 从Redis哈希表中获取指定座位类型的余票数量[1,2](@ref)
            Object stockObj = stringRedisTemplate.opsForHash().get(
                    TRAIN_STATION_REMAINING_TICKET + keySuffix,
                    String.valueOf(seatType)
            );

            // 使用Optional处理可能为null的余票值：若缓存存在直接转换，否则从缓存加载器获取[7,13,16](@ref)
            int stock = Optional.ofNullable(stockObj)
                    .map(each -> Integer.parseInt(each.toString()))
                    .orElseGet(() -> {  // 当缓存无数据时延迟加载
                        // 调用缓存加载器获取座位余量数据[2](@ref)
                        Map<String, String> seatMarginMap = seatMarginCacheLoader.load(
                                String.valueOf(requestParam.getTrainId()),
                                String.valueOf(seatType),
                                requestParam.getDeparture(),
                                requestParam.getArrival()
                        );
                        // 再次使用Optional处理可能为空的余票值[7,9](@ref)
                        return Optional.ofNullable(seatMarginMap.get(String.valueOf(seatType)))
                                .map(Integer::parseInt)
                                .orElse(0);  // 最终无数据时返回0
                    });

            // 检查余票是否满足当前座位类型的乘客数量需求
            if (stock >= passengerSeatDetails.size()) {
                return; // 余票充足，继续检查其他座位类型
            }
            throw new ClientException("列车站点已无余票");
        });
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
