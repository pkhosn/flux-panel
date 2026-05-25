package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.RedeemCodeDto;
import com.admin.common.lang.R;
import com.admin.entity.RedeemCode;
import com.admin.service.RedeemService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/redeem")
public class RedeemController extends BaseController {

    @Resource
    private RedeemService redeemService;

    @RequireRole
    @PostMapping("/list")
    public R list() {
        return redeemService.listCodes();
    }

    @RequireRole
    @PostMapping("/create")
    public R create(@RequestBody RedeemCode code) {
        return redeemService.createCode(code);
    }

    @RequireRole
    @PostMapping("/update")
    public R update(@RequestBody RedeemCode code) {
        return redeemService.updateCode(code);
    }

    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        return redeemService.deleteCode(Long.valueOf(params.get("id").toString()));
    }

    @PostMapping("/use")
    public R use(@RequestBody RedeemCodeDto dto) {
        return redeemService.useRedeemCode(dto);
    }
}
