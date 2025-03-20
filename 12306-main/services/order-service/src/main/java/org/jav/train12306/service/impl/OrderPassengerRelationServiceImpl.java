package org.jav.train12306.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jav.train12306.dao.entity.OrderItemPassengerDO;
import org.jav.train12306.dao.mapper.OrderItemPassengerMapper;
import org.jav.train12306.service.OrderPassengerRelationService;
import org.springframework.stereotype.Service;

@Service
public class OrderPassengerRelationServiceImpl extends ServiceImpl<OrderItemPassengerMapper, OrderItemPassengerDO> implements OrderPassengerRelationService {

}
