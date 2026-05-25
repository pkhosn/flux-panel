package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.OrderRecord;

import java.util.Map;

public interface PaymentService {
    R createPayment(OrderRecord order);

    R handleMgateNotify(Map<String, String> params);
}
