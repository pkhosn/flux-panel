package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UserNodePermissionDto {

    @NotNull(message = "用户ID不能为空")
    private Integer userId;

    @NotNull(message = "节点ID不能为空")
    private Integer nodeId;

    @NotNull(message = "入口权限不能为空")
    private Integer allowIn;

    @NotNull(message = "出口权限不能为空")
    private Integer allowOut;
}
