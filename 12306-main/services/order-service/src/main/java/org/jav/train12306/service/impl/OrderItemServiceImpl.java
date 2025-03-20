package org.jav.train12306.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jav.train12306.dao.entity.OrderItemDO;
import org.jav.train12306.dao.mapper.OrderItemMapper;
import org.jav.train12306.dto.domain.OrderItemStatusReversalDTO;
import org.jav.train12306.dto.req.TicketOrderItemQueryReqDTO;
import org.jav.train12306.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.springframework.stereotype.Service;
import org.jav.train12306.service.OrderItemService;

import java.util.Collection;
import java.util.List;

@Service
public class OrderItemServiceImpl extends ServiceImpl<OrderItemMapper, OrderItemDO> implements OrderItemService {
    @Override
    public void orderItemStatusReversal(OrderItemStatusReversalDTO requestParam) {

    }

    @Override
    public List<TicketOrderPassengerDetailRespDTO> queryTicketItemOrderById(TicketOrderItemQueryReqDTO requestParam) {
        return List.of();
    }

}
