package com.admin.controller;

import com.admin.common.dto.RedeemCodeDto;
import com.admin.common.lang.R;
import com.admin.service.RedeemService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/redeem")
public class RedeemController extends BaseController {

    @Resource
    private RedeemService redeemService;

    @PostMapping("/use")
    public R use(@RequestBody RedeemCodeDto dto) {
        return redeemService.useRedeemCode(dto);
    }
}
