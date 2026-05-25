package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RedeemCode extends BaseEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String code;
    private Long flowGb;
    private Integer forwardNum;
    private Long durationDays;
    private Integer used;
    private Long usedBy;
    private Long usedTime;
}
