package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.Plan;

public interface PlanService {
    R listPlans();
    R createPlan(Plan plan);
    R updatePlan(Plan plan);
    R deletePlan(Long id);
}
