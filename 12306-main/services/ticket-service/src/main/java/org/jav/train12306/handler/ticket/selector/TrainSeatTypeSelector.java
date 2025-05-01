package org.jav.train12306.handler.ticket.selector;


import cn.hutool.core.collection.CollUtil;
import org.jav.train12306.framework.starter.convention.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jav.train12306.common.enums.VehicleSeatTypeEnum;
import org.jav.train12306.common.enums.VehicleTypeEnum;
import org.jav.train12306.dao.entity.TrainStationPriceDO;
import org.jav.train12306.dao.mapper.TrainStationPriceMapper;
import org.jav.train12306.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.framework.starter.convention.exception.RemoteException;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.jav.train12306.frameworks.starter.user.core.UserContext;
import org.jav.train12306.handler.ticket.dto.SelectSeatDTO;
import org.jav.train12306.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.jav.train12306.remote.UserRemoteService;
import org.jav.train12306.remote.dto.PassengerRespDTO;
import org.jav.train12306.service.SeatService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Future;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Component
public class TrainSeatTypeSelector {
    private final SeatService seatService;
    private final UserRemoteService userRemoteService;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final AbstractStrategyChoose abstractStrategyChoose;
    //从ioc注入定义的bean
    private final ThreadPoolExecutor selectSeatThreadPoolExecutor;

    public List<TrainPurchaseTicketRespDTO> select(Integer trainType, PurchaseTicketReqDTO requestParam) {
        List<PurchaseTicketPassengerDetailDTO> passengerDetails=requestParam.getPassengers();
        Map<Integer,List<PurchaseTicketPassengerDetailDTO>> seatTypeMap=passengerDetails.stream().collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> actualResult= Collections.synchronizedList(new ArrayList<>(seatTypeMap.size()));
        if(seatTypeMap.size()>1){
            // 初始化 Future 列表，用于存储多线程分配座位的结果
            List<Future<List<TrainPurchaseTicketRespDTO>>> futureResults = new ArrayList<>(seatTypeMap.size());
            //用线程池提交不同的座位类型的 票的购买
            //submit和execute的区别 submit是出现异常的话 放在future对象 需要.get()方法才能 得到执行结果 但是还是调用的execute方法
            seatTypeMap.forEach((seatType, passengersDetail)->{
                Future<List<TrainPurchaseTicketRespDTO>> completableFuture=selectSeatThreadPoolExecutor.submit(()->distributeSeats(trainType,seatType,requestParam,passengersDetail));
                futureResults.add(completableFuture);
            });
            long startTime = System.currentTimeMillis();
            futureResults.parallelStream().forEach(
                    futureResult->{
                        try {
                            // 获取 Future 的执行结果，并添加到最终结果列表
                            actualResult.addAll(futureResult.get());
                        } catch (Exception e) {
                            // 如果座位分配失败，抛出异常提示用户更换座位类型或站点
                            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
                        }
                    }
            );
            //可能的优化 去掉并行流 因为座位类型并不是很多
/*            futureResults.stream().forEach(
                    futureResult -> {
                        try {
                            // 获取 Future 的执行结果，并添加到最终结果列表
                            actualResult.addAll(futureResult.get());
                        } catch (Exception e) {
                            // 如果座位分配失败，抛出异常提示用户更换座位类型或站点
                            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
                        }
                    }
            );
*/
            long endTime = System.currentTimeMillis();
            System.out.println("执行时间: " + (endTime - startTime) + "ms");
        }else{
            seatTypeMap.forEach((seatType, passengersDetail)->{
             List<TrainPurchaseTicketRespDTO> completableFuture=distributeSeats(trainType,seatType,requestParam,passengersDetail);
                actualResult.addAll(completableFuture);
            });
//            直接获取到键值对
/*            Map.Entry<Integer,List<PurchaseTicketPassengerDetailDTO>> entry=seatTypeMap.entrySet().iterator().next();;
            List<TrainPurchaseTicketRespDTO> completableFuture=distributeSeats(trainType,entry.getKey(),requestParam,entry.getValue());
            actualResult.addAll(completableFuture);*/
        }
        // 检查分配结果是否为空或数量是否与乘客数匹配
        if (CollUtil.isEmpty(actualResult) || !Objects.equals(actualResult.size(), passengerDetails.size())) {
            // 如果分配失败，抛出异常提示用户更换座位类型或站点
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }

        // 从分配结果中提取乘客ID列表
        List<String> passengerIds = actualResult.stream()
                .map(TrainPurchaseTicketRespDTO::getPassengerId)
                .collect(Collectors.toList());
        // 定义变量，用于存储远程调用用户服务的结果
        Result<List<PassengerRespDTO>> passengerRemoteResult;
        // 定义变量，用于存储远程返回的乘客信息列表
        List<PassengerRespDTO> passengerRemoteResultList;

        // 使用 try-catch 块处理远程调用用户服务的异常
        try {
            // 远程调用用户服务，根据乘客ID查询详细信息
            passengerRemoteResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
            // 检查远程调用是否成功且返回数据是否为空
            if (!passengerRemoteResult.isSuccess() || CollUtil.isEmpty(passengerRemoteResultList = passengerRemoteResult.getData())) {
                // 如果调用失败或返回数据为空，抛出远程调用异常
                throw new RemoteException("用户服务远程调用查询乘车人相关信息错误");
            }
        } catch (Throwable ex) {
            // 根据异常类型记录不同日志
            if (ex instanceof RemoteException) {
                // 记录远程调用异常日志，包含当前用户和请求参数
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds);
            } else {
                // 记录其他异常日志，包含异常堆栈
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds, ex);
            }
            // 抛出原始异常，终止流程
            throw ex;
        }

        // 遍历分配结果，填充乘客详细信息和票价
        actualResult.forEach(each -> {
            // 获取当前分配结果中的乘客ID
            String passengerId = each.getPassengerId();
            // 从远程返回的乘客信息中查找匹配的乘客
            passengerRemoteResultList.stream()
                    .filter(item -> Objects.equals(item.getId(), passengerId))
                    .findFirst()
                    .ifPresent(passenger -> {
                        // 填充乘客的身份证号
                        each.setIdCard(passenger.getIdCard());
                        // 填充乘客的手机号
                        each.setPhone(passenger.getPhone());
                        // 填充乘客的折扣类型（用户类型）
                        each.setUserType(passenger.getDiscountType());
                        // 填充乘客的证件类型
                        each.setIdType(passenger.getIdType());
                        // 填充乘客的真实姓名
                        each.setRealName(passenger.getRealName());
                    });
            // 创建查询条件，获取列车站点价格信息
            LambdaQueryWrapper<TrainStationPriceDO> lambdaQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                    .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                    .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                    .eq(TrainStationPriceDO::getSeatType, each.getSeatType())
                    .select(TrainStationPriceDO::getPrice); // 只查询价格字段
            // 执行查询，获取价格信息
            TrainStationPriceDO trainStationPriceDO = trainStationPriceMapper.selectOne(lambdaQueryWrapper);
            // 设置票价金额
            each.setAmount(trainStationPriceDO.getPrice());
        });

        // 锁定分配的座位，更新余票状态
        // 中间站点余票更新问题参考：https://nageoffer.com/12306/question
        seatService.lockSeat(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival(), actualResult);
        // 返回最终的购票结果
        return actualResult;
    }
    private List<TrainPurchaseTicketRespDTO> distributeSeats(Integer trainType, Integer seatType, PurchaseTicketReqDTO requestParam, List<PurchaseTicketPassengerDetailDTO> passengerSeatDetails) {
        // 构建策略键，由列车类型名称和座位类型名称组成
        String buildStrategyKey = VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        // 创建座位选择 DTO，封装分配参数
        SelectSeatDTO selectSeatDTO = SelectSeatDTO.builder()
                .seatType(seatType)
                .passengerSeatDetails(passengerSeatDetails)
                .requestParam(requestParam)
                .build();

        // 使用 try-catch 块处理座位分配中的异常
        try {
            // 使用策略模式选择并执行座位分配策略，返回分配结果
            return abstractStrategyChoose.chooseAndExecuteResp(buildStrategyKey, selectSeatDTO);
        } catch (ServiceException ex) {
            // 如果分配失败，抛出异常提示用户更换车次
            throw new ServiceException("当前车次列车类型暂未适配，请购买G35或G39车次");
        }
    }
}
