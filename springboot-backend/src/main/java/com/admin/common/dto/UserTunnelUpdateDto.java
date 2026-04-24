package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class UserTunnelUpdateDto {
    
    /**
     * 用户隧道权限ID（可选）
     * 允许通过 id 或 userId+tunnelId 定位记录，提升接口兼容性
     */
    private Integer id;

    /**
     * 用户ID（可选，和 tunnelId 搭配作为备用定位条件）
     */
    private Integer userId;

    /**
     * 隧道ID（可选，和 userId 搭配作为备用定位条件）
     */
    private Integer tunnelId;
    
    @NotNull(message = "流量限制不能为空")
    @Min(value = 0, message = "流量限制不能小于0")
    private Long flow;
    
    @NotNull(message = "转发数量不能为空")
    @Min(value = 0, message = "转发数量不能小于0")
    private Integer num;
    
    /**
     * 流量重置时间（时间戳）
     */
    @NotNull(message = "流量重置时间不能为空")
    private Long flowResetTime;
    
    /**
     * 到期时间（时间戳）
     */
    @NotNull(message = "到期时间不能为空")
    private Long expTime;

    @NotNull(message = "状态必选")
    private Integer status;
    
    /**
     * 限速规则ID（可选，null表示不限速）
     */
    private Integer speedId;
} 
