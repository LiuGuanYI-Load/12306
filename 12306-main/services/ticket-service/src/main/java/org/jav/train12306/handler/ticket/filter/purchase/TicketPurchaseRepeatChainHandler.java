package org.jav.train12306.handler.ticket.filter.purchase;

import org.jav.train12306.dto.req.PurchaseTicketReqDTO;
import org.springframework.stereotype.Component;

@Component
public class TicketPurchaseRepeatChainHandler implements TrainPurchaseTicketChainHandler<PurchaseTicketReqDTO>{
    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {

    }
}
