package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UserNodePermissionUpdateDto {

    @NotNull(message = "权限记录ID不能为空")
    private Long id;

    @NotNull(message = "入口权限不能为空")
    private Integer allowIn;

    @NotNull(message = "出口权限不能为空")
    private Integer allowOut;
}
