package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class CreateOrderDto {
    @NotNull(message = "套餐ID不能为空")
    private Long planId;

    private String payType;
}
