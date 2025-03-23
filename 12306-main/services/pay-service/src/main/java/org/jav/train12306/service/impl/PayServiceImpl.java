package org.jav.train12306.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jav.train12306.common.enums.TradeStatusEnum;
import org.jav.train12306.convert.RefundRequestConvert;
import org.jav.train12306.dao.entity.PayDO;
import org.jav.train12306.dao.mapper.PayMapper;
import org.jav.train12306.dto.*;
import org.jav.train12306.dto.base.PayRequest;
import org.jav.train12306.dto.base.PayResponse;
import org.jav.train12306.dto.base.RefundRequest;
import org.jav.train12306.dto.base.RefundResponse;
import org.jav.train12306.framework.starter.cache.DistributedCache;
import org.jav.train12306.framework.starter.common.toolkit.BeanUtil;
import org.jav.train12306.framework.starter.convention.exception.ServiceException;
import org.jav.train12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.jav.train12306.framework.starter.idempotent.annotation.Idempotent;
import org.jav.train12306.framework.starter.idempotent.enums.IdempotentTypeEnum;
import org.jav.train12306.mq.event.PayResultCallbackOrderEvent;
import org.jav.train12306.mq.produce.PayResultCallbackOrderSendProduce;
import org.jav.train12306.service.PayService;
import org.jav.train12306.service.payid.PayIdGeneratorManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.jav.train12306.common.constant.RedisKeyConstant.ORDER_NO_PAY_INFO;

@RequiredArgsConstructor
@Slf4j
@Service
public class PayServiceImpl implements PayService {
    private final PayMapper payMapper;
    private final AbstractStrategyChoose abstractStrategyChoose;
    private final PayResultCallbackOrderSendProduce payResultCallbackOrderSendProduce;
    private final DistributedCache distributedCache;



    // 提炼更新方法
    private void updatePayDO(PayDO payDO, String orderSn, String operation) {
        LambdaUpdateWrapper<PayDO> updateWrapper = Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, orderSn);
        int result = payMapper.update(payDO, updateWrapper);
        if (result <= 0) {
            log.error("修改支付单{}失败，支付单信息：{}", operation, JSON.toJSONString(payDO));
            throw new ServiceException("修改支付单" + operation + "失败");
        }
        log.info("修改支付单{}成功，支付单信息：{}", operation, JSON.toJSONString(payDO));
    }

    @Idempotent(
            type = IdempotentTypeEnum.SPEL,
            uniqueKeyPrefix = "index12306-pay:lock_create_pay:",
            key = "#requestParam.getOutOrderSn()"
    )
    @Transactional(rollbackFor = Exception.class)
    @Override
    public PayRespDTO commonPay(PayRequest requestParam) {
        PayRespDTO result = distributedCache.get(ORDER_NO_PAY_INFO + requestParam.getOrderSn(), PayRespDTO.class);
        if (result != null) {
            return result;
        }
        PayResponse payResponse = abstractStrategyChoose.chooseAndExecuteResp(requestParam.buildMark(), requestParam);
        PayDO insertPay = BeanUtil.convert(requestParam, PayDO.class);
        String paySn = PayIdGeneratorManager.generateId(requestParam.getOrderSn());
        insertPay.setPaySn(paySn);
        insertPay.setStatus(TradeStatusEnum.WAIT_BUYER_PAY.tradeCode());
        insertPay.setTotalAmount(requestParam.getTotalAmount().multiply(new BigDecimal("100"))
                .setScale(0, BigDecimal.ROUND_HALF_UP).intValue());
        int insert = payMapper.insert(insertPay);
        if (insert <= 0) {
            log.error("支付单创建失败，支付聚合根：{}", JSON.toJSONString(requestParam));
            throw new ServiceException("支付单创建失败");
        }
        distributedCache.put(ORDER_NO_PAY_INFO + requestParam.getOrderSn(), JSON.toJSONString(payResponse), 10, TimeUnit.MINUTES);
        return BeanUtil.convert(payResponse, PayRespDTO.class);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void callbackPay(PayCallbackReqDTO requestParam) {
        PayDO payDO = getPayDOByOrderSn(requestParam.getOrderSn());
        payDO.setTradeNo(requestParam.getTradeNo());
        payDO.setStatus(requestParam.getStatus());
        payDO.setPayAmount(requestParam.getPayAmount());
        payDO.setGmtPayment(requestParam.getGmtPayment());
        updatePayDO(payDO, requestParam.getOrderSn(), "支付结果");
        if (Objects.equals(requestParam.getStatus(), TradeStatusEnum.TRADE_SUCCESS.tradeCode())) {
            payResultCallbackOrderSendProduce.sendMessage(BeanUtil.convert(payDO, PayResultCallbackOrderEvent.class));
        }
    }

    @Override
    public PayInfoRespDTO getPayInfoByOrderSn(String orderSn) {
        PayDO payDO = getPayDOByOrderSn(orderSn);
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }

    @Override
    public PayInfoRespDTO getPayInfoByPaySn(String paySn) {
        PayDO payDO = getPayDOByPaySn(paySn);
        return BeanUtil.convert(payDO, PayInfoRespDTO.class);
    }
    // 提炼查询方法
    private PayDO getPayDOByOrderSn(String orderSn) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getOrderSn, orderSn);
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (Objects.isNull(payDO)) {
            log.error("支付单不存在，orderSn：{}", orderSn);
            throw new ServiceException("支付单不存在");
        }
        return payDO;
    }

    private PayDO getPayDOByPaySn(String paySn) {
        LambdaQueryWrapper<PayDO> queryWrapper = Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getPaySn, paySn);
        PayDO payDO = payMapper.selectOne(queryWrapper);
        if (Objects.isNull(payDO)) {
            log.error("支付单不存在，paySn：{}", paySn);
            throw new ServiceException("支付单不存在");
        }
        return payDO;
    }

    @Override
    public RefundRespDTO commonRefund(RefundReqDTO requestParam) {
        PayDO payDO = getPayDOByOrderSn(requestParam.getOrderSn());
        RefundCommand refundCommand = BeanUtil.convert(payDO, RefundCommand.class);
        RefundRequest refundRequest = RefundRequestConvert.command2RefundRequest(refundCommand);
        RefundResponse result = abstractStrategyChoose.chooseAndExecuteResp(refundRequest.buildMark(), refundRequest);
        payDO.setStatus(result.getStatus());
        updatePayDO(payDO, requestParam.getOrderSn(), "退款结果");
        return BeanUtil.convert(result, RefundRespDTO.class);
    }
}