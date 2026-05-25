package com.admin.service.impl;

import cn.hutool.core.map.MapUtil;
import com.admin.common.lang.R;
import com.admin.entity.OrderRecord;
import com.admin.entity.Plan;
import com.admin.entity.User;
import com.admin.entity.ViteConfig;
import com.admin.mapper.OrderRecordMapper;
import com.admin.mapper.PlanMapper;
import com.admin.service.PaymentService;
import com.admin.service.UserService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    @Resource
    private ViteConfigService viteConfigService;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private OrderRecordMapper orderRecordMapper;

    @Resource
    private UserService userService;

    @Resource
    private PlanMapper planMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public R createPayment(OrderRecord order) {
        String paymentEnabled = getConfig("payment_enabled");
        if (!"1".equals(paymentEnabled) && !"true".equalsIgnoreCase(paymentEnabled)) {
            return R.err("支付未启用，请联系管理员配置 payment_enabled=1");
        }

        String provider = normalizeProvider(getConfig("payment_provider"));
        if (!StringUtils.hasText(provider)) {
            return R.err("未配置 payment_provider");
        }

        String notifyBaseUrl = getConfig("payment_notify_base_url");
        if (!StringUtils.hasText(notifyBaseUrl)) {
            return R.err("未配置回调地址，请先配置 payment_notify_base_url");
        }
        String returnBaseUrl = getConfig("payment_return_base_url");
        if (!StringUtils.hasText(returnBaseUrl)) {
            returnBaseUrl = notifyBaseUrl;
        }

        String notifyUrl = trimSlash(notifyBaseUrl) + "/api/v1/order/notify/" + provider;
        String returnUrl = trimSlash(returnBaseUrl) + "/billing?orderNo=" + order.getOrderNo();

        switch (provider) {
            case "mgate":
                return createMgatePayment(order, notifyUrl, returnUrl);
            case "epay":
                return createEpayPayment(order, notifyUrl, returnUrl);
            case "bepusdt":
                return createBepusdtPayment(order, notifyUrl, returnUrl);
            case "coinpayments":
                return createCoinpaymentsPayment(order, notifyUrl, returnUrl);
            case "coinbase":
                return createCoinbasePayment(order, notifyUrl, returnUrl);
            case "btcpay":
                return createBtcpayPayment(order, notifyUrl, returnUrl);
            case "stripe_checkout":
            case "stripe_alipay":
            case "stripe_wepay":
            case "stripe_credit":
            case "stripe_all":
                return createStripeCheckoutPayment(order, notifyUrl, returnUrl);
            default:
                return R.err("暂不支持的支付通道: " + provider);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R handleNotify(String provider,
                          Map<String, String> params,
                          String rawBody,
                          String stripeSignature,
                          String btcpaySignature,
                          String coinbaseSignature,
                          String coinpaymentsHmac) {
        String p = normalizeProvider(provider);
        String orderNo;
        String callbackNo;

        switch (p) {
            case "mgate":
                orderNo = verifyMgate(params);
                callbackNo = params.getOrDefault("trade_no", "");
                break;
            case "epay":
                orderNo = verifyEpay(params);
                callbackNo = params.getOrDefault("trade_no", "");
                break;
            case "bepusdt":
                orderNo = verifyBepusdt(params);
                callbackNo = params.getOrDefault("trade_no", "");
                break;
            case "coinpayments":
                orderNo = verifyCoinpayments(params, coinpaymentsHmac);
                callbackNo = params.getOrDefault("txn_id", "");
                break;
            case "coinbase":
                orderNo = verifyCoinbase(rawBody, coinbaseSignature);
                callbackNo = "coinbase";
                break;
            case "btcpay":
                orderNo = verifyBtcpay(rawBody, btcpaySignature);
                callbackNo = "btcpay";
                break;
            case "stripe_checkout":
            case "stripe_alipay":
            case "stripe_wepay":
            case "stripe_credit":
            case "stripe_all":
                orderNo = verifyStripe(rawBody, stripeSignature);
                callbackNo = "stripe";
                break;
            default:
                return R.err("未知支付通道");
        }

        if (!StringUtils.hasText(orderNo)) {
            return R.err("回调订单号无效");
        }

        OrderRecord order = orderRecordMapper.selectOne(new QueryWrapper<OrderRecord>().eq("order_no", orderNo));
        if (order == null) return R.err("订单不存在");
        if (order.getStatus() != null && order.getStatus() == 1) return R.ok("success");

        R settleResult = settlePaidOrder(order, callbackNo);
        if (settleResult.getCode() != 0) return settleResult;
        return R.ok("success");
    }

    private R createMgatePayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String apiUrl = getConfig("payment_mgate_url");
        String appId = getConfig("payment_mgate_app_id");
        String appSecret = getConfig("payment_mgate_app_secret");
        String sourceCurrency = getConfig("payment_mgate_source_currency");
        if (!StringUtils.hasText(sourceCurrency)) sourceCurrency = "CNY";
        if (!StringUtils.hasText(apiUrl) || !StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return R.err("支付配置不完整，请检查 payment_mgate_* 配置");
        }

        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", order.getOrderNo());
        params.put("total_amount", String.valueOf(order.getAmount()));
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("source_currency", sourceCurrency);
        params.put("app_id", appId);
        params.put("sign", md5(httpBuildQuerySorted(params) + appSecret));

        try {
            Map body = postFormForMap(trimSlash(apiUrl) + "/v1/gateway/fetch", params, "MGate");
            Map data = (Map) body.get("data");
            if (data == null || !StringUtils.hasText(str(data.get("pay_url")))) return R.err("未获取到支付链接");
            return savePayUrlAndReturn(order, str(data.get("pay_url")));
        } catch (Exception e) {
            log.error("Create mgate payment failed, orderNo={}", order.getOrderNo(), e);
            return R.err("创建支付订单失败: " + e.getMessage());
        }
    }

    private R createEpayPayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String url = getConfig("payment_epay_url");
        String pid = getConfig("payment_epay_pid");
        String key = getConfig("payment_epay_key");
        if (!StringUtils.hasText(url) || !StringUtils.hasText(pid) || !StringUtils.hasText(key)) {
            return R.err("支付配置不完整，请检查 payment_epay_* 配置");
        }

        Map<String, String> params = new HashMap<>();
        params.put("money", String.format(Locale.US, "%.2f", order.getAmount() / 100.0));
        params.put("name", order.getOrderNo());
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("out_trade_no", order.getOrderNo());
        params.put("pid", pid);
        String sign = md5(urldecode(httpBuildQuerySorted(params)) + key);
        params.put("sign", sign);
        params.put("sign_type", "MD5");
        String payUrl = trimSlash(url) + "/submit.php?" + httpBuildQuery(params);
        return savePayUrlAndReturn(order, payUrl);
    }

    private R createBepusdtPayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String url = getConfig("payment_bepusdt_url");
        String token = getConfig("payment_bepusdt_apitoken");
        if (!StringUtils.hasText(url) || !StringUtils.hasText(token)) {
            return R.err("支付配置不完整，请检查 payment_bepusdt_* 配置");
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("user_transaction_id", order.getOrderNo());
        req.put("amount", order.getAmount() / 100.0);
        req.put("goods_title", order.getPlanName());
        req.put("notify_url", notifyUrl);
        req.put("return_url", returnUrl);
        String sign = md5(urldecode(httpBuildQueryObjectSorted(req)) + token);
        req.put("signature", sign);
        Map body = postJsonForMap(trimSlash(url) + "/api/v1/order/create-transaction", req, "BEPUSDT");
        Object resultObj = body.get("data");
        if (!(resultObj instanceof Map)) return R.err("BEPUSDT返回异常");
        String payUrl = str(((Map) resultObj).get("payment_url"));
        if (!StringUtils.hasText(payUrl)) payUrl = str(((Map) resultObj).get("pay_url"));
        if (!StringUtils.hasText(payUrl)) return R.err("未获取到支付链接");
        return savePayUrlAndReturn(order, payUrl);
    }

    private R createCoinpaymentsPayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String merchant = getConfig("payment_coinpayments_merchant_id");
        if (!StringUtils.hasText(merchant)) return R.err("请配置 payment_coinpayments_merchant_id");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cmd", "_pay");
        params.put("reset", "1");
        params.put("merchant", merchant);
        params.put("item_name", order.getOrderNo());
        params.put("invoice", order.getOrderNo());
        params.put("amountf", String.format(Locale.US, "%.2f", order.getAmount() / 100.0));
        params.put("currency", "USD");
        params.put("want_shipping", "0");
        params.put("success_url", returnUrl);
        params.put("cancel_url", returnUrl);
        params.put("ipn_url", notifyUrl);
        String payUrl = "https://www.coinpayments.net/index.php?" + httpBuildQuery(params);
        return savePayUrlAndReturn(order, payUrl);
    }

    private R createCoinbasePayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String endpoint = getConfig("payment_coinbase_url");
        String apiKey = getConfig("payment_coinbase_api_key");
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(apiKey)) {
            return R.err("支付配置不完整，请检查 payment_coinbase_* 配置");
        }

        Map<String, Object> req = new HashMap<>();
        req.put("name", order.getOrderNo());
        req.put("description", order.getPlanName());
        req.put("pricing_type", "fixed_price");
        req.put("local_price", Map.of("amount", String.format(Locale.US, "%.2f", order.getAmount() / 100.0), "currency", "USD"));
        req.put("metadata", Map.of("trade_no", order.getOrderNo()));
        req.put("redirect_url", returnUrl);
        req.put("cancel_url", returnUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-CC-Api-Key", apiKey);
        headers.set("X-CC-Version", "2018-03-22");

        Map body = postJsonForMap(trimSlash(endpoint), req, headers);
        Map data = (Map) body.get("data");
        if (data == null || !StringUtils.hasText(str(data.get("hosted_url")))) return R.err("未获取到支付链接");
        return savePayUrlAndReturn(order, str(data.get("hosted_url")));
    }

    private R createBtcpayPayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String host = getConfig("payment_btcpay_url");
        String storeId = getConfig("payment_btcpay_store_id");
        String apiKey = getConfig("payment_btcpay_api_key");
        String currency = getConfig("payment_btcpay_currency");
        if (!StringUtils.hasText(currency)) currency = "USD";
        if (!StringUtils.hasText(host) || !StringUtils.hasText(storeId) || !StringUtils.hasText(apiKey)) {
            return R.err("支付配置不完整，请检查 payment_btcpay_* 配置");
        }
        Map<String, Object> req = new HashMap<>();
        req.put("amount", String.format(Locale.US, "%.2f", order.getAmount() / 100.0));
        req.put("currency", currency);
        req.put("metadata", Map.of("orderId", order.getOrderNo()));
        req.put("checkout", Map.of("redirectURL", returnUrl));
        req.put("notificationURL", notifyUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String url = trimSlash(host) + "/api/v1/stores/" + storeId + "/invoices";
        Map body = postJsonForMap(url, req, headers);
        if (!StringUtils.hasText(str(body.get("checkoutLink")))) return R.err("未获取到支付链接");
        return savePayUrlAndReturn(order, str(body.get("checkoutLink")));
    }

    private R createStripeCheckoutPayment(OrderRecord order, String notifyUrl, String returnUrl) {
        String secret = getConfig("payment_stripe_sk_live");
        String currency = getConfig("payment_stripe_currency");
        if (!StringUtils.hasText(currency)) currency = "usd";
        if (!StringUtils.hasText(secret)) return R.err("请配置 payment_stripe_sk_live");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("success_url", returnUrl);
        req.put("cancel_url", returnUrl);
        req.put("client_reference_id", order.getOrderNo());
        req.put("mode", "payment");

        Map<String, Object> lineItem = new LinkedHashMap<>();
        lineItem.put("quantity", "1");
        lineItem.put("price_data[currency]", currency.toLowerCase(Locale.ROOT));
        lineItem.put("price_data[unit_amount]", String.valueOf(order.getAmount()));
        lineItem.put("price_data[product_data][name]", order.getPlanName());

        Map<String, String> form = new LinkedHashMap<>();
        form.put("success_url", returnUrl);
        form.put("cancel_url", returnUrl);
        form.put("client_reference_id", order.getOrderNo());
        form.put("mode", "payment");
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", currency.toLowerCase(Locale.ROOT));
        form.put("line_items[0][price_data][unit_amount]", String.valueOf(order.getAmount()));
        form.put("line_items[0][price_data][product_data][name]", order.getPlanName());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(secret);

        Map body = postFormForMapWithHeaders("https://api.stripe.com/v1/checkout/sessions", form, headers);
        String payUrl = str(body.get("url"));
        if (!StringUtils.hasText(payUrl)) return R.err("未获取到支付链接");
        return savePayUrlAndReturn(order, payUrl);
    }

    private String verifyMgate(Map<String, String> params) {
        String secret = getConfig("payment_mgate_app_secret");
        String sign = params.get("sign");
        Map<String, String> copy = new HashMap<>(params);
        copy.remove("sign");
        String expected = md5(httpBuildQuerySorted(copy) + secret);
        if (!Objects.equals(expected, sign)) throw new RuntimeException("签名校验失败");
        return copy.get("out_trade_no");
    }

    private String verifyEpay(Map<String, String> params) {
        String key = getConfig("payment_epay_key");
        String sign = params.get("sign");
        Map<String, String> copy = new HashMap<>(params);
        copy.remove("sign");
        copy.remove("sign_type");
        String expected = md5(urldecode(httpBuildQuerySorted(copy)) + key);
        if (!Objects.equals(expected, sign)) throw new RuntimeException("签名校验失败");
        if (!"TRADE_SUCCESS".equals(copy.getOrDefault("trade_status", ""))) throw new RuntimeException("支付未成功");
        return copy.get("out_trade_no");
    }

    private String verifyBepusdt(Map<String, String> params) {
        String token = getConfig("payment_bepusdt_apitoken");
        String sign = params.get("signature");
        Map<String, String> copy = new HashMap<>(params);
        copy.remove("signature");
        String expected = md5(urldecode(httpBuildQuerySorted(copy)) + token);
        if (!Objects.equals(expected, sign)) throw new RuntimeException("签名校验失败");
        return copy.get("user_transaction_id");
    }

    private String verifyCoinpayments(Map<String, String> params, String hmacHeader) {
        String secret = getConfig("payment_coinpayments_ipn_secret");
        if (!StringUtils.hasText(secret)) throw new RuntimeException("未配置 coinpayments secret");
        String request = httpBuildQuery(params);
        String hmac = hmacSha512(request, secret.trim());
        if (!Objects.equals(hmac, hmacHeader)) throw new RuntimeException("HMAC signature does not match");
        String status = params.getOrDefault("status", "0");
        if (!status.startsWith("1") && !status.startsWith("2") && !status.startsWith("100")) {
            throw new RuntimeException("payment not completed");
        }
        return params.get("invoice");
    }

    private String verifyCoinbase(String rawBody, String signature) {
        String webhook = getConfig("payment_coinbase_webhook_key");
        if (!StringUtils.hasText(webhook)) throw new RuntimeException("未配置 coinbase webhook key");
        String computed = hmacSha256(rawBody, webhook);
        if (!constantEquals(computed, signature)) throw new RuntimeException("HMAC signature does not match");
        try {
            Map body = objectMapper.readValue(rawBody, Map.class);
            Map event = (Map) body.get("event");
            if (event == null) throw new RuntimeException("coinbase body invalid");
            String type = str(event.get("type"));
            if (!"charge:confirmed".equals(type)) throw new RuntimeException("event not confirmed");
            Map data = (Map) event.get("data");
            Map metadata = (Map) data.get("metadata");
            return str(metadata.get("trade_no"));
        } catch (Exception e) {
            throw new RuntimeException("解析 coinbase 回调失败");
        }
    }

    private String verifyBtcpay(String rawBody, String signature) {
        String webhook = getConfig("payment_btcpay_webhook_key");
        if (!StringUtils.hasText(webhook)) throw new RuntimeException("未配置 btcpay webhook key");
        String computed = "sha256=" + hmacSha256(rawBody, webhook);
        if (!constantEquals(computed, signature)) throw new RuntimeException("HMAC signature does not match");
        try {
            Map body = objectMapper.readValue(rawBody, Map.class);
            String type = str(body.get("type"));
            if (!"InvoiceSettled".equals(type) && !"InvoiceReceivedPayment".equals(type)) {
                throw new RuntimeException("event not settled");
            }
            Map metadata = (Map) body.get("metadata");
            return str(metadata.get("orderId"));
        } catch (Exception e) {
            throw new RuntimeException("解析 btcpay 回调失败");
        }
    }

    private String verifyStripe(String rawBody, String signature) {
        String webhook = getConfig("payment_stripe_webhook_key");
        if (!StringUtils.hasText(webhook)) throw new RuntimeException("未配置 stripe webhook key");
        if (!StringUtils.hasText(signature)) throw new RuntimeException("缺少 Stripe-Signature");
        String timestamp = "";
        String v1 = "";
        String[] parts = signature.split(",");
        for (String p : parts) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) {
                if ("t".equals(kv[0])) timestamp = kv[1];
                if ("v1".equals(kv[0])) v1 = kv[1];
            }
        }
        String payload = timestamp + "." + rawBody;
        String expected = hmacSha256(payload, webhook);
        if (!constantEquals(expected, v1)) throw new RuntimeException("stripe signature mismatch");
        try {
            Map body = objectMapper.readValue(rawBody, Map.class);
            String type = str(body.get("type"));
            if (!"checkout.session.completed".equals(type) && !"checkout.session.async_payment_succeeded".equals(type)) {
                throw new RuntimeException("event not supported");
            }
            Map data = (Map) body.get("data");
            Map object = (Map) data.get("object");
            return str(object.get("client_reference_id"));
        } catch (Exception e) {
            throw new RuntimeException("解析 stripe 回调失败");
        }
    }

    private R savePayUrlAndReturn(OrderRecord order, String payUrl) {
        order.setPayUrl(payUrl);
        order.setUpdatedTime(System.currentTimeMillis());
        orderRecordMapper.updateById(order);
        return R.ok(MapUtil.builder().put("payUrl", payUrl).put("orderNo", order.getOrderNo()).build());
    }

    private R settlePaidOrder(OrderRecord order, String callbackNo) {
        Plan plan = planMapper.selectById(order.getPlanId());
        if (plan == null || Objects.equals(plan.getStatus(), 0)) return R.err("套餐不存在或已下架");

        User user = userService.getById(order.getUserId());
        if (user == null) return R.err("用户不存在");

        user.setFlow((user.getFlow() == null ? 0 : user.getFlow()) + (plan.getFlowGb() == null ? 0 : plan.getFlowGb()));
        user.setNum((user.getNum() == null ? 0 : user.getNum()) + (plan.getForwardNum() == null ? 0 : plan.getForwardNum()));

        long now = System.currentTimeMillis();
        long addDurationMs = (plan.getDurationDays() == null ? 0 : plan.getDurationDays()) * 24L * 60L * 60L * 1000L;
        if (user.getExpTime() == null || user.getExpTime() < now) user.setExpTime(now + addDurationMs);
        else user.setExpTime(user.getExpTime() + addDurationMs);
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
        orderRecordMapper.updateById(order);
        return R.ok();
    }

    private String getConfig(String name) {
        ViteConfig cfg = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", name));
        return cfg == null ? "" : cfg.getValue();
    }

    private String normalizeProvider(String provider) {
        if (provider == null) return "";
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trimSlash(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Map postFormForMap(String url, Map<String, String> params, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("User-Agent", userAgent);
        return restTemplate.postForEntity(url, new HttpEntity<>(httpBuildQuery(params), headers), Map.class).getBody();
    }

    private Map postFormForMapWithHeaders(String url, Map<String, String> params, HttpHeaders headers) {
        return restTemplate.postForEntity(url, new HttpEntity<>(httpBuildQuery(params), headers), Map.class).getBody();
    }

    private Map postJsonForMap(String url, Map<String, Object> body, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", userAgent);
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map postJsonForMap(String url, Map<String, Object> body, HttpHeaders headers) {
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private String httpBuildQuery(Map<String, String> params) {
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) body.append("&");
            first = false;
            body.append(e.getKey()).append("=").append(urlEncode(e.getValue()));
        }
        return body.toString();
    }

    private String httpBuildQuerySorted(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>(params);
        return httpBuildQuery(sorted);
    }

    private String httpBuildQueryObjectSorted(Map<String, Object> params) {
        Map<String, String> m = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) m.put(e.getKey(), str(e.getValue()));
        return httpBuildQuery(m);
    }

    private String urlEncode(String input) {
        if (input == null) return "";
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    private String urldecode(String input) {
        return input.replace("+", " ");
    }

    private String str(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String hmacSha256(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String hmacSha512(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
