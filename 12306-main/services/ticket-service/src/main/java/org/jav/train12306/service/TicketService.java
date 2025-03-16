package org.jav.train12306.service;


import org.jav.train12306.dto.req.CancelTicketOrderReqDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.dto.req.RefundTicketReqDTO;
import org.jav.train12306.dto.req.TicketPageQueryReqDTO;
import org.jav.train12306.dto.resp.RefundTicketRespDTO;
import org.jav.train12306.dto.resp.TicketPageQueryRespDTO;
import org.jav.train12306.dto.resp.TicketPurchaseRespDTO;
import org.jav.train12306.remote.dto.PayInfoRespDTO;
import org.springframework.web.bind.annotation.RequestBody;

public interface TicketService {


    TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam);

    TicketPurchaseRespDTO purchaseTicketsV2(@RequestBody PurchaseTicketReqDTO requestParam);


    /**
     * 执行购买车票
     * 被对应购票版本号接口调用{@link TicketService#purchaseTicketsV2(PurchaseTicketReqDTO)}
     *
     * @param requestParam 车票购买请求参数
     * @return 订单号
     */
    TicketPurchaseRespDTO executePurchaseTickets(@RequestBody PurchaseTicketReqDTO requestParam);

    /**
     * 支付单详情查询
     *
     * @param orderSn 订单号
     * @return 支付单详情
     */
    PayInfoRespDTO getPayInfo(String orderSn);

    /**
     * 取消车票订单
     *
     * @param requestParam 取消车票订单入参
     */
    void cancelTicketOrder(CancelTicketOrderReqDTO requestParam);

    /**
     * 公共退款接口
     *
     * @param requestParam 退款请求参数
     * @return 退款返回详情
     */
    RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam);
}
