package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UserNodePermissionQueryDto {

    @NotNull(message = "用户ID不能为空")
    private Integer userId;
}
