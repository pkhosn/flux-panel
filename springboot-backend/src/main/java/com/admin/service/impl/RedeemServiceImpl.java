package com.admin.service.impl;

import com.admin.common.dto.RedeemCodeDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.RedeemCode;
import com.admin.entity.User;
import com.admin.mapper.RedeemCodeMapper;
import com.admin.service.RedeemService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class RedeemServiceImpl extends ServiceImpl<RedeemCodeMapper, RedeemCode> implements RedeemService {

    @Resource
    private UserService userService;

    private void seedDefaults() {
        if (count() > 0) {
            return;
        }
        long now = System.currentTimeMillis();
        List<RedeemCode> codes = new ArrayList<>();
        codes.add(buildCode("FX-TRIAL-100", 100L, 10, 7L));
        codes.add(buildCode("FX-START-200", 200L, 20, 15L));
        codes.add(buildCode("FX-PRO-500", 500L, 50, 30L));
        for (RedeemCode code : codes) {
            code.setCreatedTime(now);
            code.setUpdatedTime(now);
            code.setStatus(1);
            super.save(code);
        }
    }

    private RedeemCode buildCode(String code, Long flowGb, Integer forwardNum, Long durationDays) {
        RedeemCode redeemCode = new RedeemCode();
        redeemCode.setCode(code);
        redeemCode.setFlowGb(flowGb);
        redeemCode.setForwardNum(forwardNum);
        redeemCode.setDurationDays(durationDays);
        redeemCode.setUsed(0);
        return redeemCode;
    }

    @Override
    public R useRedeemCode(RedeemCodeDto dto) {
        seedDefaults();
        Integer userId = JwtUtil.getUserIdFromToken();
        User user = userService.getById(userId);
        if (user == null) return R.err("用户不存在");

        RedeemCode redeemCode = getOne(new QueryWrapper<RedeemCode>().eq("code", dto.getCode()));
        if (redeemCode == null) return R.err("兑换码不存在");
        if (redeemCode.getUsed() != null && redeemCode.getUsed() == 1) return R.err("兑换码已使用");

        user.setFlow((user.getFlow() == null ? 0 : user.getFlow()) + redeemCode.getFlowGb());
        user.setNum((user.getNum() == null ? 0 : user.getNum()) + redeemCode.getForwardNum());
        if (user.getExpTime() == null || user.getExpTime() < System.currentTimeMillis()) {
            user.setExpTime(System.currentTimeMillis() + redeemCode.getDurationDays() * 24L * 60L * 60L * 1000L);
        } else {
            user.setExpTime(user.getExpTime() + redeemCode.getDurationDays() * 24L * 60L * 60L * 1000L);
        }
        user.setUpdatedTime(System.currentTimeMillis());
        userService.updateById(user);

        redeemCode.setUsed(1);
        redeemCode.setUsedBy(user.getId());
        redeemCode.setUsedTime(System.currentTimeMillis());
        redeemCode.setUpdatedTime(System.currentTimeMillis());
        updateById(redeemCode);
        return R.ok();
    }
}
