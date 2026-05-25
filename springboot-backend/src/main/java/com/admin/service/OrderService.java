package com.admin.service;

import com.admin.common.dto.CreateOrderDto;
import com.admin.common.lang.R;

import java.util.Map;

public interface OrderService {
    R createOrder(CreateOrderDto dto);
    R repayOrder(Long id);
    R getMyOrderList();
    R getAllOrderList(Map<String, Object> params);
    R deleteOrder(Long id);
    R clearOrders(Map<String, Object> params);
}
