package com.admin.service.impl;

import cn.hutool.core.map.MapUtil;
import com.admin.common.dto.CreateOrderDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.OrderRecord;
import com.admin.entity.Plan;
import com.admin.entity.User;
import com.admin.mapper.OrderRecordMapper;
import com.admin.mapper.PlanMapper;
import com.admin.service.OrderService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderRecordMapper, OrderRecord> implements OrderService {

    @Resource
    private PlanMapper planMapper;

    @Resource
    private UserService userService;

    @PostConstruct
    public void initSeed() {
        // no-op; plans are seeded in PlanServiceImpl
    }

    @Override
    public R createOrder(CreateOrderDto dto) {
        if (planMapper.selectCount(null) == 0) {
            planMapper.insert(buildPlan("基础版", "适合个人试用", 10L, 100L, 10, 30L, 999));
            planMapper.insert(buildPlan("标准版", "适合日常使用", 30L, 300L, 30, 30L, 500));
            planMapper.insert(buildPlan("旗舰版", "适合重度使用", 60L, 800L, 80, 30L, 200));
        }
        Integer userId = JwtUtil.getUserIdFromToken();
        User user = userService.getById(userId);
        if (user == null) return R.err("用户不存在");

        Plan plan = planMapper.selectById(dto.getPlanId());
        if (plan == null || Objects.equals(plan.getStatus(), 0)) return R.err("套餐不存在");
        if (plan.getStock() != null && plan.getStock() == 0) return R.err("套餐已售罄");

        OrderRecord order = new OrderRecord();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(user.getId());
        order.setUserName(user.getUser());
        order.setPlanId(plan.getId());
        order.setPlanName(plan.getName());
        order.setAmount(plan.getPrice());
        order.setPayType(dto.getPayType());
        order.setStatus(0);
        order.setPayUrl("http://127.0.0.1:6366/billing?orderNo=" + order.getOrderNo());
        order.setCreatedTime(System.currentTimeMillis());
        order.setUpdatedTime(System.currentTimeMillis());
        save(order);
        return R.ok(MapUtil.builder()
                .put("payUrl", order.getPayUrl())
                .put("orderNo", order.getOrderNo())
                .build());
    }

    private Plan buildPlan(String name, String description, Long price, Long flowGb, Integer forwardNum, Long durationDays, Integer stock) {
        Plan plan = new Plan();
        plan.setName(name);
        plan.setDescription(description);
        plan.setPrice(price);
        plan.setFlowGb(flowGb);
        plan.setForwardNum(forwardNum);
        plan.setDurationDays(durationDays);
        plan.setStock(stock);
        plan.setStatus(1);
        plan.setCreatedTime(System.currentTimeMillis());
        plan.setUpdatedTime(System.currentTimeMillis());
        return plan;
    }

    @Override
    public R repayOrder(Long id) {
        OrderRecord order = getById(id);
        if (order == null) return R.err("订单不存在");
        return R.ok(MapUtil.builder()
                .put("payUrl", order.getPayUrl())
                .put("orderNo", order.getOrderNo())
                .build());
    }

    @Override
    public R getMyOrderList() {
        Integer userId = JwtUtil.getUserIdFromToken();
        return R.ok(list(new QueryWrapper<OrderRecord>().eq("user_id", userId).orderByDesc("id")));
    }

    @Override
    public R getAllOrderList(Map<String, Object> params) {
        if (JwtUtil.getRoleIdFromToken() != 0) {
            return R.err("无权限");
        }
        QueryWrapper<OrderRecord> wrapper = new QueryWrapper<>();
        Object status = params.get("status");
        Object keyword = params.get("keyword");
        if (status != null && !"".equals(status.toString())) {
            wrapper.eq("status", Integer.parseInt(status.toString()));
        }
        if (keyword != null && !keyword.toString().trim().isEmpty()) {
            String key = keyword.toString().trim();
            wrapper.and(w -> w.like("order_no", key).or().like("user_name", key).or().like("plan_name", key));
        }
        return R.ok(list(wrapper.orderByDesc("id")));
    }

    @Override
    public R deleteOrder(Long id) {
        if (JwtUtil.getRoleIdFromToken() != 0) {
            return R.err("无权限");
        }
        removeById(id);
        return R.ok();
    }

    @Override
    public R clearOrders(Map<String, Object> params) {
        if (JwtUtil.getRoleIdFromToken() != 0) {
            return R.err("无权限");
        }
        QueryWrapper<OrderRecord> wrapper = new QueryWrapper<>();
        Object status = params.get("status");
        Object keyword = params.get("keyword");
        if (status != null && !"".equals(status.toString())) {
            wrapper.eq("status", Integer.parseInt(status.toString()));
        }
        if (keyword != null && !keyword.toString().trim().isEmpty()) {
            String key = keyword.toString().trim();
            wrapper.and(w -> w.like("order_no", key).or().like("user_name", key).or().like("plan_name", key));
        }
        remove(wrapper);
        return R.ok();
    }
}
