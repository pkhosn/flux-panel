package com.admin.service.impl;

import cn.hutool.core.map.MapUtil;
import com.admin.common.lang.R;
import com.admin.entity.OrderRecord;
import com.admin.entity.Plan;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.PlanMapper;
import com.admin.service.OrderService;
import com.admin.service.PaymentService;
import com.admin.service.UserService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    @Resource
    private ViteConfigService viteConfigService;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    @Resource
    private PlanMapper planMapper;

    @Override
    public R createPayment(OrderRecord order) {
        String paymentEnabled = getConfig("payment_enabled");
        if (!"1".equals(paymentEnabled)) {
            return R.err("支付未启用，请联系管理员配置 payment_enabled=1");
        }
        String provider = getConfig("payment_provider");
        if (!"mgate".equalsIgnoreCase(provider)) {
            return R.err("当前仅支持 mgate 支付通道");
        }

        String apiUrl = getConfig("payment_mgate_url");
        String appId = getConfig("payment_mgate_app_id");
        String appSecret = getConfig("payment_mgate_app_secret");
        String sourceCurrency = getConfig("payment_mgate_source_currency");
        if (!StringUtils.hasText(sourceCurrency)) {
            sourceCurrency = "CNY";
        }

        String notifyBaseUrl = getConfig("payment_notify_base_url");
        if (!StringUtils.hasText(notifyBaseUrl)) {
            return R.err("未配置回调地址，请先配置 payment_notify_base_url");
        }
        String returnBaseUrl = getConfig("payment_return_base_url");
        if (!StringUtils.hasText(returnBaseUrl)) {
            returnBaseUrl = notifyBaseUrl;
        }

        if (!StringUtils.hasText(apiUrl) || !StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return R.err("支付配置不完整，请检查 payment_mgate_* 配置");
        }

        String notifyUrl = trimSlash(notifyBaseUrl) + "/api/v1/order/notify/mgate";
        String returnUrl = trimSlash(returnBaseUrl) + "/billing?orderNo=" + order.getOrderNo();

        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", order.getOrderNo());
        params.put("total_amount", String.valueOf(order.getAmount()));
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("source_currency", sourceCurrency);
        params.put("app_id", appId);

        String sign = sign(params, appSecret);
        params.put("sign", sign);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("User-Agent", "MGate");

        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) body.append("&");
            first = false;
            body.append(e.getKey()).append("=").append(urlEncode(e.getValue()));
        }

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    trimSlash(apiUrl) + "/v1/gateway/fetch",
                    new HttpEntity<>(body.toString(), headers),
                    Map.class
            );
            Map responseBody = response.getBody();
            if (responseBody == null) {
                return R.err("支付网关返回为空");
            }
            Object dataObj = responseBody.get("data");
            if (!(dataObj instanceof Map)) {
                return R.err("支付网关返回格式错误");
            }
            Map data = (Map) dataObj;
            Object payUrlObj = data.get("pay_url");
            if (payUrlObj == null || !StringUtils.hasText(payUrlObj.toString())) {
                return R.err("未获取到支付链接");
            }
            String payUrl = payUrlObj.toString();
            order.setPayUrl(payUrl);
            order.setUpdatedTime(System.currentTimeMillis());
            orderService.updateById(order);

            return R.ok(MapUtil.builder()
                    .put("payUrl", payUrl)
                    .put("orderNo", order.getOrderNo())
                    .build());
        } catch (Exception e) {
            log.error("Create mgate payment failed, orderNo={}", order.getOrderNo(), e);
            return R.err("创建支付订单失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R handleMgateNotify(Map<String, String> params) {
        String appSecret = getConfig("payment_mgate_app_secret");
        if (!StringUtils.hasText(appSecret)) {
            return R.err("支付配置不完整");
        }

        String sign = params.get("sign");
        if (!StringUtils.hasText(sign)) {
            return R.err("签名缺失");
        }

        Map<String, String> copy = new HashMap<>(params);
        copy.remove("sign");
        String expected = sign(copy, appSecret);
        if (!Objects.equals(expected, sign)) {
            return R.err("签名校验失败");
        }

        String orderNo = copy.get("out_trade_no");
        if (!StringUtils.hasText(orderNo)) {
            return R.err("订单号缺失");
        }

        OrderRecord order = orderService.getOne(new QueryWrapper<OrderRecord>().eq("order_no", orderNo));
        if (order == null) {
            return R.err("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == 1) {
            return R.ok("success");
        }

        Plan plan = planMapper.selectById(order.getPlanId());
        if (plan == null || Objects.equals(plan.getStatus(), 0)) {
            return R.err("套餐不存在或已下架");
        }

        User user = userService.getById(order.getUserId());
        if (user == null) {
            return R.err("用户不存在");
        }

        // 发放权益（与兑换码一致）：流量/可建转发数累加，时长叠加
        user.setFlow((user.getFlow() == null ? 0 : user.getFlow()) + (plan.getFlowGb() == null ? 0 : plan.getFlowGb()));
        user.setNum((user.getNum() == null ? 0 : user.getNum()) + (plan.getForwardNum() == null ? 0 : plan.getForwardNum()));
        long now = System.currentTimeMillis();
        long addDurationMs = (plan.getDurationDays() == null ? 0 : plan.getDurationDays()) * 24L * 60L * 60L * 1000L;
        if (user.getExpTime() == null || user.getExpTime() < now) {
            user.setExpTime(now + addDurationMs);
        } else {
            user.setExpTime(user.getExpTime() + addDurationMs);
        }
        user.setUpdatedTime(now);
        userService.updateById(user);

        if (plan.getStock() != null && plan.getStock() > 0) {
            plan.setStock(plan.getStock() - 1);
            plan.setUpdatedTime(now);
            planMapper.updateById(plan);
        }

        order.setStatus(1);
        order.setPaidTime(now);
        order.setUpdatedTime(now);
        orderService.updateById(order);

        return R.ok("success");
    }

    private String getConfig(String name) {
        ViteConfig cfg = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", name));
        return cfg == null ? "" : cfg.getValue();
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String sign(Map<String, String> params, String secret) {
        Map<String, String> sorted = new HashMap<>(params);
        StringBuilder sb = new StringBuilder();
        sorted.keySet().stream().sorted().forEach(k -> {
            if (sb.length() > 0) sb.append("&");
            sb.append(k).append("=").append(params.get(k));
        });
        sb.append(secret);
        return md5(sb.toString());
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 error", e);
        }
    }

    private String urlEncode(String input) {
        return java.net.URLEncoder.encode(input, StandardCharsets.UTF_8);
    }
}
