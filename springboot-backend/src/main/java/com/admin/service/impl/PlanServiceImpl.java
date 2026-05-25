package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.Plan;
import com.admin.mapper.PlanMapper;
import com.admin.service.PlanService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlanServiceImpl extends ServiceImpl<PlanMapper, Plan> implements PlanService {

    @Override
    public R listPlans() {
        if (count() == 0) {
            long now = System.currentTimeMillis();
            List<Plan> plans = new ArrayList<>();
            plans.add(buildPlan("基础版", "适合个人试用", 10L, 100L, 10, 30L, 999));
            plans.add(buildPlan("标准版", "适合日常使用", 30L, 300L, 30, 30L, 500));
            plans.add(buildPlan("旗舰版", "适合重度使用", 60L, 800L, 80, 30L, 200));
            for (Plan plan : plans) {
                plan.setCreatedTime(now);
                plan.setUpdatedTime(now);
                plan.setStatus(1);
                save(plan);
            }
        }
        return R.ok(list());
    }

    @Override
    public R createPlan(Plan plan) {
        if (plan == null || plan.getName() == null || plan.getName().trim().isEmpty()) {
            return R.err("套餐名称不能为空");
        }
        long now = System.currentTimeMillis();
        plan.setCreatedTime(now);
        plan.setUpdatedTime(now);
        if (plan.getStatus() == null) plan.setStatus(1);
        if (plan.getPrice() == null) plan.setPrice(0L);
        if (plan.getFlowGb() == null) plan.setFlowGb(0L);
        if (plan.getForwardNum() == null) plan.setForwardNum(0);
        if (plan.getDurationDays() == null) plan.setDurationDays(0L);
        if (plan.getStock() == null) plan.setStock(0);
        save(plan);
        return R.ok(plan);
    }

    @Override
    public R updatePlan(Plan plan) {
        if (plan == null || plan.getId() == null) return R.err("参数错误");
        Plan existed = getById(plan.getId());
        if (existed == null) return R.err("套餐不存在");
        plan.setUpdatedTime(System.currentTimeMillis());
        updateById(plan);
        return R.ok(getById(plan.getId()));
    }

    @Override
    public R deletePlan(Long id) {
        if (id == null) return R.err("参数错误");
        remove(new QueryWrapper<Plan>().eq("id", id));
        return R.ok();
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
        return plan;
    }
}
