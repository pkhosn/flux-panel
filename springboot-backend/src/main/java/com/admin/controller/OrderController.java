package com.admin.controller;

import com.admin.common.dto.CreateOrderDto;
import com.admin.common.lang.R;
import com.admin.service.OrderService;
import com.admin.service.PaymentService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/order")
public class OrderController extends BaseController {

    @Resource
    private OrderService orderService;

    @Resource
    private PaymentService paymentService;

    @PostMapping("/create")
    public R create(@RequestBody CreateOrderDto dto) {
        return orderService.createOrder(dto);
    }

    @PostMapping("/repay")
    public R repay(@RequestBody Map<String, Object> params) {
        return orderService.repayOrder(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/my-list")
    public R myList() {
        return orderService.getMyOrderList();
    }

    @PostMapping("/list")
    public R list(@RequestBody Map<String, Object> params) {
        return orderService.getAllOrderList(params);
    }

    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        return orderService.deleteOrder(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/clear")
    public R clear(@RequestBody Map<String, Object> params) {
        return orderService.clearOrders(params);
    }

    @RequestMapping(value = "/notify/{provider}", method = {RequestMethod.GET, RequestMethod.POST})
    public String notifyProvider(@PathVariable String provider,
                                 @RequestParam Map<String, String> params,
                                 HttpServletRequest request) {
        String rawBody = "";
        try {
            BufferedReader reader = request.getReader();
            rawBody = reader.lines().collect(Collectors.joining());
        } catch (Exception ignored) {
        }

        R result = paymentService.handleNotify(
                provider,
                params,
                rawBody,
                request.getHeader("Stripe-Signature"),
                request.getHeader("BTCPay-Sig"),
                request.getHeader("X-CC-Webhook-Signature"),
                request.getHeader("HMAC")
        );
        return result.getCode() == 0 ? "success" : "fail";
    }
}
