package org.jav.train12306.controller;

import lombok.RequiredArgsConstructor;
import org.jav.train12306.dto.req.CancelTicketOrderReqDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.dto.req.RefundTicketReqDTO;
import org.jav.train12306.dto.req.TicketPageQueryReqDTO;
import org.jav.train12306.dto.resp.RefundTicketRespDTO;
import org.jav.train12306.dto.resp.TicketPageQueryRespDTO;
import org.jav.train12306.dto.resp.TicketPurchaseRespDTO;
import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.framework.starter.web.Results;
import org.jav.train12306.remote.dto.PayInfoRespDTO;
import org.jav.train12306.service.TicketService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ticket-service/ticket")
public class TicketController {
    private final TicketService ticketService;
    /**
     * 根据条件查询车票
     */
    @GetMapping("/query")
    public Result<TicketPageQueryRespDTO> pageListTicketQuery(TicketPageQueryReqDTO requestParam) {
        return Results.success(ticketService.pageListTicketQueryV2(requestParam));
    }

    /**
     * 购买车票
     */
    @PostMapping("/purchase/v2")
    public Result<TicketPurchaseRespDTO> purchaseTicketsV2(@RequestBody PurchaseTicketReqDTO requestParam) {
        return Results.success(ticketService.purchaseTicketsV2(requestParam));
    }

    /**
     * 取消车票订单
     */
    @PostMapping("/cancel")
    public Result<Void> cancelTicketOrder(@RequestBody CancelTicketOrderReqDTO requestParam) {
        ticketService.cancelTicketOrder(requestParam);
        return Results.success();
    }

    /**
     * 支付单详情查询
     */
    @GetMapping("/pay/query")
    public Result<PayInfoRespDTO> getPayInfo(@RequestParam(value = "orderSn") String orderSn) {
        return Results.success(ticketService.getPayInfo(orderSn));
    }

    /**
     * 公共退款接口
     */
    @PostMapping("/refund")
    public Result<RefundTicketRespDTO> commonTicketRefund(@RequestBody RefundTicketReqDTO requestParam) {
        return Results.success(ticketService.commonTicketRefund(requestParam));
    }
    /**
     * 使用V2版本的查询
    */
    @PostMapping
    public Result<TicketPageQueryRespDTO> pageQueryList(@RequestBody TicketPageQueryReqDTO requestparam){
        return Results.success(ticketService.pageListTicketQueryV2(requestparam));
    }
}
