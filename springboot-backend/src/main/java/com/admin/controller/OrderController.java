package com.admin.controller;

import com.admin.common.dto.CreateOrderDto;
import com.admin.common.lang.R;
import com.admin.service.OrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/order")
public class OrderController extends BaseController {

    @Resource
    private OrderService orderService;

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
}
