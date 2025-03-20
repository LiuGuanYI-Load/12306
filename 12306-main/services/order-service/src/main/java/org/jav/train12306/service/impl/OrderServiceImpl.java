package org.jav.train12306.service.impl;

import cn.crane4j.annotation.AutoOperate;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.text.StrBuilder;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.jav.train12306.common.enums.OrderCanalErrorCodeEnum;
import org.jav.train12306.common.enums.OrderItemStatusEnum;
import org.jav.train12306.common.enums.OrderStatusEnum;
import org.jav.train12306.dao.entity.OrderDO;
import org.jav.train12306.dao.entity.OrderItemDO;
import org.jav.train12306.dao.entity.OrderItemPassengerDO;
import org.jav.train12306.dao.mapper.OrderItemMapper;
import org.jav.train12306.dao.mapper.OrderMapper;
import org.jav.train12306.dto.domain.OrderStatusReversalDTO;
import org.jav.train12306.dto.req.*;
import org.jav.train12306.dto.resp.TicketOrderDetailRespDTO;
import org.jav.train12306.dto.resp.TicketOrderDetailSelfRespDTO;
import org.jav.train12306.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.jav.train12306.framework.starter.common.toolkit.BeanUtil;
import org.jav.train12306.framework.starter.convention.exception.ClientException;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.framework.starter.convention.page.PageResponse;
import org.jav.train12306.framework.starter.database.toolkit.PageUtil;
import org.jav.train12306.frameworks.starter.user.core.UserContext;
import org.jav.train12306.mq.event.DelayCloseOrderEvent;
import org.jav.train12306.mq.event.PayResultCallbackOrderEvent;
import org.jav.train12306.mq.produce.DelayCloseOrderSendProduce;
import org.jav.train12306.remote.UserRemoteService;
import org.jav.train12306.remote.dto.UserQueryActualRespDTO;
import org.jav.train12306.service.OrderItemService;
import org.jav.train12306.service.OrderPassengerRelationService;
import org.jav.train12306.service.OrderService;
import org.jav.train12306.service.orderid.OrderIdGeneratorManager;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    // 主订单数据访问
    private final OrderItemMapper orderItemMapper;
    // 订单项数据访问
    private final OrderItemService orderItemService;
    // 订单项服务
    private final OrderPassengerRelationService orderPassengerRelationService;
    // 订单乘客关系服务
    private final RedissonClient redissonClient;

    private final UserRemoteService userRemoteService;

    // 分布式锁客户端
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;
    //支付回调
    @Override
    public void payCallbackOrder(PayResultCallbackOrderEvent requestParam) {
        // 构建要更新的数据：设置支付时间和支付渠道
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setPayTime(requestParam.getGmtPayment());
        updateOrderDO.setPayType(requestParam.getChannel());
        // 构建更新条件：根据订单号更新 相当于where
        LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        // 执行更新操作
        int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
        // 校验更新结果
        if (updateResult <= 0) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    /**
     * 用户查询本人历史订单
     */
    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        // 远程调用用户服务，获取用户实名信息
        Result<UserQueryActualRespDTO> userActualResp = userRemoteService.queryActualUserByUsername(UserContext.getUsername());
        // 构建查询条件：根据身份证查询订单乘客关系
        LambdaQueryWrapper<OrderItemPassengerDO> queryWrapper = Wrappers.lambdaQuery(OrderItemPassengerDO.class)
                .eq(OrderItemPassengerDO::getIdCard, userActualResp.getData().getIdCard())
                .orderByDesc(OrderItemPassengerDO::getCreateTime);
        // 执行分页查询
        IPage<OrderItemPassengerDO> orderItemPassengerPage = orderPassengerRelationService.page(PageUtil.convert(requestParam), queryWrapper);
        // 转换分页结果，补充订单详情
        return PageUtil.convert(orderItemPassengerPage, each -> {
            // 查询主订单信息
            LambdaQueryWrapper<OrderDO> orderQueryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                    .eq(OrderDO::getOrderSn, each.getOrderSn());
            OrderDO orderDO = orderMapper.selectOne(orderQueryWrapper);
            // 查询订单项信息
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn())
                    .eq(OrderItemDO::getIdCard, each.getIdCard());
            OrderItemDO orderItemDO = orderItemMapper.selectOne(orderItemQueryWrapper);
            // 转换并返回结果
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            BeanUtil.convertIgnoreNullAndBlank(orderItemDO, actualResult);
            return actualResult;
        });
    }
    /**
     * 订单状态反转
     * 代码优化 更正订单反转逻辑
     */
    @Override
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        // 查询订单信息
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        // 校验订单状态
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        // 获取分布式锁
        RLock lock = redissonClient.getLock(StrBuilder.create("order:status-reversal:order_sn_").append(requestParam.getOrderSn()).toString());
        if (!lock.tryLock()) {
            log.warn("订单重复修改状态，状态反转请求参数：{}", JSON.toJSONString(requestParam));
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_GETLOCK_ERROR);
        }
        try {
                // 更新主订单状态
                OrderDO updateOrderDO = new OrderDO();
                updateOrderDO.setStatus(requestParam.getOrderStatus());
                LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                        .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
                int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
                if (updateResult <= 0) {
                    throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
                }
                // 更新订单项状态
                OrderItemDO orderItemDO = new OrderItemDO();
                orderItemDO.setStatus(requestParam.getOrderItemStatus());
                LambdaUpdateWrapper<OrderItemDO> orderItemUpdateWrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                        .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn());
                int orderItemUpdateResult = orderItemMapper.update(orderItemDO, orderItemUpdateWrapper);
                if (orderItemUpdateResult <= 0) {
                    throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
                }
        } finally {
                // 释放分布式锁
                lock.unlock();
        }


    }
    /**
     * 取消订单
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean cancelTickOrder(CancelTicketOrderReqDTO requestParam) {
        //取消订单第一步 先查订单
        String orderSn = requestParam.getOrderSn();
        // 查询订单信息
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        // 校验订单是否存在
        if (orderDO == null) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        } else if (orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        // 获取分布式锁
        RLock lock = redissonClient.getLock(StrBuilder.create("order:canal:order_sn_").append(orderSn).toString());
        if (!lock.tryLock()) {
            //抛出异常跳出方法 不用返回false；
            throw new ClientException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            //更新status
            OrderDO updateOrderDO=new OrderDO();
            updateOrderDO.setStatus(OrderStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper=Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn,requestParam.getOrderSn());
            int updateResult = orderMapper.update(updateOrderDO, updateWrapper);
            if (updateResult <= 0) {
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
        } finally {
            // 释放锁
            lock.unlock();
        }
        return true;
    }


    /**
     * 关闭待支付订单
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean closeTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        // 查询订单状态
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .select(OrderDO::getStatus);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        // 校验订单状态是否为待支付
        if (Objects.isNull(orderDO) || orderDO.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getStatus()) {
            return false;
        }

        // 调用取消订单逻辑
        return cancelTickOrder(requestParam);
    }
    /**
     * 创建火车票订单
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        // 使用基因法生成订单号，融入用户ID分片信息
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        // 构建主订单实体
        OrderDO orderDO = OrderDO.builder()
                .orderSn(orderSn)
                .orderTime(requestParam.getOrderTime())
                .departure(requestParam.getDeparture())
                .departureTime(requestParam.getDepartureTime())
                .ridingDate(requestParam.getRidingDate())
                .arrivalTime(requestParam.getArrivalTime())
                .trainNumber(requestParam.getTrainNumber())
                .arrival(requestParam.getArrival())
                .trainId(requestParam.getTrainId())
                .source(requestParam.getSource())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .username(requestParam.getUsername())
                .userId(String.valueOf(requestParam.getUserId()))
                .build();
        // 插入主订单数据
        orderMapper.insert(orderDO);
        // 处理订单项数据
        List<TicketOrderItemCreateReqDTO> ticketOrderItems = requestParam.getTicketOrderItems();
        List<OrderItemDO> orderItemDOList = new ArrayList<>();
        List<OrderItemPassengerDO> orderPassengerRelationDOList = new ArrayList<>();
        ticketOrderItems.forEach(each -> {
            // 构建订单项实体
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .trainId(requestParam.getTrainId())
                    .seatNumber(each.getSeatNumber())
                    .carriageNumber(each.getCarriageNumber())
                    .realName(each.getRealName())
                    .orderSn(orderSn)
                    .phone(each.getPhone())
                    .seatType(each.getSeatType())
                    .username(requestParam.getUsername()).amount(each.getAmount()).carriageNumber(each.getCarriageNumber())
                    .idCard(each.getIdCard())
                    .ticketType(each.getTicketType())
                    .idType(each.getIdType())
                    .userId(String.valueOf(requestParam.getUserId()))
                    .status(0)
                    .build();
            orderItemDOList.add(orderItemDO);
            // 构建订单乘客关系实体
            OrderItemPassengerDO orderPassengerRelationDO = OrderItemPassengerDO.builder()
                    .idType(each.getIdType())
                    .idCard(each.getIdCard())
                    .orderSn(orderSn)
                    .build();
            orderPassengerRelationDOList.add(orderPassengerRelationDO);
        });
        // 批量插入订单项和乘客关系数据
        orderItemService.saveBatch(orderItemDOList);
        orderPassengerRelationService.saveBatch(orderPassengerRelationDOList);
        try {
            // 发送 RocketMQ 延时消息，指定时间后取消订单
            DelayCloseOrderEvent delayCloseOrderEvent = DelayCloseOrderEvent.builder()
                    .trainId(String.valueOf(requestParam.getTrainId()))
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderSn(orderSn)
                    .trainPurchaseTicketResults(requestParam.getTicketOrderItems())
                    .build();
            // 创建订单并支付后延时关闭订单消息怎么办？详情查看：https://nageoffer.com/12306/question
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(delayCloseOrderEvent);
            if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)) {
                throw new ServiceException("投递延迟关闭订单消息队列失败");
            }
        } catch (Throwable ex) {
            log.error("延迟关闭订单消息队列发送错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return orderSn;
    }

    /**
     * 分页查询订单列表
     */
    @AutoOperate(type = TicketOrderDetailRespDTO.class, on = "data.records")
    @Override
    public PageResponse<TicketOrderDetailRespDTO> pageTicketOrder(TicketOrderPageQueryReqDTO requestParam) {
        // 构建查询条件：根据用户ID和状态过滤
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .in(OrderDO::getStatus, buildOrderStatusList(requestParam))
                .orderByDesc(OrderDO::getOrderTime);
        // 执行分页查询
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        // 转换分页结果，补充订单项信息
        return PageUtil.convert(orderPage, each -> {
            TicketOrderDetailRespDTO result = BeanUtil.convert(each, TicketOrderDetailRespDTO.class);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn());
            List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
            result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
            return result;
        });
    }

    /**
     * 构建订单状态过滤列表
     */
    private List<Integer> buildOrderStatusList(TicketOrderPageQueryReqDTO requestParam) {
        List<Integer> result = new ArrayList<>();
        // 根据状态类型构建过滤条件
        switch (requestParam.getStatusType()) {
            case 0 -> result = ListUtil.of(OrderStatusEnum.PENDING_PAYMENT.getStatus());
            // 待支付
            case 1 -> result = ListUtil.of(OrderStatusEnum.ALREADY_PAID.getStatus(), OrderStatusEnum.PARTIAL_REFUND.getStatus(), OrderStatusEnum.FULL_REFUND.getStatus());
            // 已支付/退款相关
            case 2 -> result = ListUtil.of(OrderStatusEnum.COMPLETED.getStatus());
            // 已完成
        }
        return result;
    }

    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        // 构建查询条件：根据订单号查询主订单
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        // 查询主订单信息
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        // 将主订单实体转换为响应DTO
        TicketOrderDetailRespDTO result = BeanUtil.convert(orderDO, TicketOrderDetailRespDTO.class);
        // 构建查询条件：根据订单号查询订单项
        LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn);
        // 查询订单项列表  根据OrderItemDO里面定义的表名查询
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
        // 将订单项实体转换为乘客详情DTO，并设置到结果中
        result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
        return result;
    }
}
