package org.jav.train12306.remote;

import org.jav.train12306.framework.starter.convention.result.Result;
import org.jav.train12306.remote.dto.PayInfoRespDTO;
import org.jav.train12306.remote.dto.RefundRespDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
//通过启动类上加上enablefeignclients
//需要的做远程调用的方法塞进去接口 到时候直接注入接口调用
/*
@EnableFeignClients 是 Spring Cloud 提供的注解，用于启用 Feign 客户端功能。
它会扫描项目中所有被 @FeignClient 注解标记的接口，并自动生成代理对象，从而可以通过这些接口调用远程服务。
 */
@FeignClient(value="train12306-pay${unique-name:}-service",url="${aggregation.remote-url:}")
public interface PayRemoteService {
    //根据订单号查询订单
    @GetMapping("/api/pay-service/pay/query")
    Result<PayInfoRespDTO> getPayInfo(@RequestParam("orderSn")String orderSn);
    //公共退款接口
    @PostMapping("/api/pay-service/common/refund")
    Result<RefundRespDTO>commonRefund(@RequestBody  RefundRespDTO refundRespDTO);
}
