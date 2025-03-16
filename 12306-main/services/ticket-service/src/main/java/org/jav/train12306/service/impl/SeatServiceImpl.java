package org.jav.train12306.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jav.train12306.common.enums.SeatStatusEnum;
import org.jav.train12306.dao.entity.SeatDO;
import org.jav.train12306.dao.mapper.SeatMapper;
import org.jav.train12306.dto.domain.RouteDTO;
import org.jav.train12306.dto.domain.SeatTypeCountDTO;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.jav.train12306.service.SeatService;
import org.jav.train12306.service.TrainStationService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.jav.train12306.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;

@RequiredArgsConstructor
@Service
public class SeatServiceImpl extends ServiceImpl<SeatMapper,SeatDO> implements SeatService{

    private final SeatMapper seatMapper;
    private final TrainStationService trainStationService;
    private final DistributedCache distributedCache;
    /**
    * @Author: Jav
    * @Date: 2025/3/16
    * @Description: 返回可用的座位号集合
    * @Param: [java.lang.String, java.lang.String, java.lang.Integer, java.lang.String, java.lang.String]
    * @return: java.util.List<java.lang.String>
    *
    */
    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        //获取可用的 座位集合 座位状态 0-可售 1-锁定 2-已售
        LambdaQueryWrapper wrapper= Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getCarriageNumber, carriageNumber)
                .eq(SeatDO::getSeatType, seatType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                //挑选可用座位 状态码必须是 AVALIABLE -0
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .select(SeatDO::getSeatNumber);
        List<SeatDO> seatDOList=seatMapper.selectList(wrapper);
        return seatDOList.stream().map(SeatDO::getSeatNumber).collect(Collectors.toList());

    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, carriageType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .groupBy(SeatDO::getCarriageNumber)
                //查出有余票的车厢号
                .select(SeatDO::getCarriageNumber);
        List<SeatDO> seatDOList = seatMapper.selectList(queryWrapper);
        return seatDOList.stream().map(SeatDO::getCarriageNumber).collect(Collectors.toList());
    }
    /**
    * @Author: Jav
    * @Date: 2025/3/16
    * @Description: 展示每节车厢的可用座位数量
    * @Param: [java.lang.String, java.lang.String, java.lang.String, java.util.List<java.lang.String>]
    * @return: java.util.List<java.lang.Integer>
    *
    */
    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        //构建key 后缀 和前缀
        //查缓存
        String keySuffix=StrUtil.join("_",trainId,departure,arrival);
        if(distributedCache.hasKey(TRAIN_STATION_CARRIAGE_REMAINING_TICKET+keySuffix)){
            StringRedisTemplate stringRedisTemplate=(StringRedisTemplate) distributedCache.getInstance();
            List<Object> trainStationCarriageRemainingTicket =
                    stringRedisTemplate.opsForHash().multiGet(TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix, Arrays.asList(trainCarriageList.toArray()));
            if (CollUtil.isNotEmpty(trainStationCarriageRemainingTicket)) {
                return trainStationCarriageRemainingTicket.stream().map(each -> Integer.parseInt(each.toString())).collect(Collectors.toList());
            }
        }
        SeatDO seatDO=SeatDO.builder().trainId(Long.parseLong(trainId)).startStation(departure).endStation(arrival).build();
//        展示每节车厢的可用座位数量
/*        select count(*) as count
        from t_seat
        where train_id = #{seatDO.trainId}
        and start_station = #{seatDO.startStation}
        and end_station = #{seatDO.endStation}
        and seat_status = '0'
        and carriage_number in
                <foreach collection="trainCarriageList" item="carriage" open="(" separator="," close=")">
            #{carriage}
        </foreach>
                group by carriage_number*/
        return seatMapper.listSeatRemainingTicket(seatDO,trainCarriageList);
    }
    /**
    * @Author: Jav
    * @Date: 2025/3/16
    * @Description: 展示不同座位类型的可用座位数量
    * @Param: [java.lang.Long, java.lang.String, java.lang.String, java.util.List<java.lang.Integer>]
    * @return: java.util.List<org.jav.train12306.dto.domain.SeatTypeCountDTO>
    *
    */
    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        //展示不同座位类型的可用座位数量
/*        select seat_type as seatType, count(*) as seatCount
        from t_seat
        where train_id = #{trainId}
        and start_station = #{startStation}
        and end_station = #{endStation}
        and seat_status = '0'
        and seat_type in
                <foreach collection="seatTypes" item="seatType" open="(" separator="," close=")">
            #{seatType}
        </foreach>
                group by seat_type
        having seatCount > 0*/
        return seatMapper.listSeatTypeCount(trainId, startStation, endStation, seatTypes);
    }

    @Override
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketRespList) {
//      List<RouteDTO> routeList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        List<RouteDTO> routeList = trainStationService.listTrainStationRoute(trainId, departure, arrival);
        trainPurchaseTicketRespList.forEach(each -> routeList.forEach(item -> {
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getSeatNumber, each.getSeatNumber());
            //build  出seatStatus 的DO更新 座位状态
            SeatDO updateSeatDO = SeatDO.builder()
                    .seatStatus(SeatStatusEnum.LOCKED.getCode())
                    .build();
            seatMapper.update(updateSeatDO, updateWrapper);
        }));
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults) {
//      List<RouteDTO> routeList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        List<RouteDTO> routeList = trainStationService.listTrainStationRoute(trainId, departure, arrival);
        trainPurchaseTicketResults.forEach(each -> routeList.forEach(item -> {
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getSeatNumber, each.getSeatNumber());
            SeatDO updateSeatDO = SeatDO.builder()
                    .seatStatus(SeatStatusEnum.AVAILABLE.getCode())
                    .build();
            seatMapper.update(updateSeatDO, updateWrapper);
        }));
    }
}
