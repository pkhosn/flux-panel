package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RedeemCodeDto {
    @NotBlank(message = "兑换码不能为空")
    private String code;
}
