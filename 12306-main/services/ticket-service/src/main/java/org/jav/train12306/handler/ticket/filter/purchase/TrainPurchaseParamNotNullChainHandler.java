package org.jav.train12306.handler.ticket.filter.purchase;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.jav.train12306.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.framework.starter.convention.exception.ClientException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class TrainPurchaseParamNotNullChainHandler implements TrainPurchaseTicketChainHandler<PurchaseTicketReqDTO>{
    @Override
    public int getOrder() {
        return 0;
    }
    /**
    * @Author: Jav
    * @Date: 2025/3/15
    * @Description: 责任链模式之检测穿参数是否非空
     * null
     * 空字符串：""
     * 空格、全角空格、制表符、换行符，等不可见字符
    * @Param: [org.jav.train12306.dto.req.PurchaseTicketReqDTO]
    * @return: void
    *
    */
    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        //blank检查是否空白
        if (StrUtil.isBlank(requestParam.getTrainId())) {
            throw new ClientException("列车标识不能为空");
        }
        if (StrUtil.isBlank(requestParam.getDeparture())) {
            throw new ClientException("出发站点不能为空");
        }
        if (StrUtil.isBlank(requestParam.getArrival())) {
            throw new ClientException("到达站点不能为空");
        }
        if (CollUtil.isEmpty(requestParam.getPassengers())) {
            throw new ClientException("乘车人至少选择一位");
        }
        for (PurchaseTicketPassengerDetailDTO each : requestParam.getPassengers()) {
            if (StrUtil.isBlank(each.getPassengerId())) {
                throw new ClientException("乘车人不能为空");
            }
            if (Objects.isNull(each.getSeatType())) {
                throw new ClientException("座位类型不能为空");
            }
        }
    }
}
