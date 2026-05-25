package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.service.PlanService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

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
}
