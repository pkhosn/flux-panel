package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.lang.R;
import com.admin.entity.Plan;
import com.admin.service.PlanService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/plan")
public class PlanController extends BaseController {

    @Resource
    private PlanService planService;

    @PostMapping("/list")
    public R list() {
        return planService.listPlans();
    }

    @RequireRole
    @PostMapping("/create")
    public R create(@RequestBody Plan plan) {
        return planService.createPlan(plan);
    }

    @RequireRole
    @PostMapping("/update")
    public R update(@RequestBody Plan plan) {
        return planService.updatePlan(plan);
    }

    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        return planService.deletePlan(Long.valueOf(params.get("id").toString()));
    }
}
