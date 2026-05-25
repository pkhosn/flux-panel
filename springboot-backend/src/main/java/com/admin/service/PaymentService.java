package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.OrderRecord;

import java.util.Map;

public interface PaymentService {
    R createPayment(OrderRecord order);

    R handleNotify(String provider, Map<String, String> params, String rawBody, String stripeSignature, String btcpaySignature, String coinbaseSignature, String coinpaymentsHmac);
}
