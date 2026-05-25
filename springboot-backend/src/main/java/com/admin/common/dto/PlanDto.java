package com.admin.common.dto;

import lombok.Data;

@Data
public class PlanDto {
    private Long id;
    private String name;
    private String description;
    private Long price;
    private Long flowGb;
    private Integer forwardNum;
    private Long durationDays;
    private Integer status;
    private Integer stock;
}
