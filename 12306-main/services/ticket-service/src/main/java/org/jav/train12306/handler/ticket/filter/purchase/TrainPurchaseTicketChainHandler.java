package org.jav.train12306.handler.ticket.filter.purchase;

import org.jav.train12306.common.enums.TicketChainMarkEnum;
import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.jav.train12306.framework.starter.designpattern.chain.AbstractChainHandler;

public interface TrainPurchaseTicketChainHandler <T extends PurchaseTicketReqDTO> extends AbstractChainHandler<PurchaseTicketReqDTO> {
    @Override
    //使用default字段修饰的 方法才能有方法体
    default  String mark(){return TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name();}
}
