package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.Plan;
import com.admin.mapper.PlanMapper;
import com.admin.service.PlanService;
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
