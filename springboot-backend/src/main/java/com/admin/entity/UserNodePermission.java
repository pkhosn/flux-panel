package com.admin.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserNodePermission implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Integer userId;

    private Integer nodeId;

    private Integer allowIn;

    private Integer allowOut;

    private Long createdTime;

    private Long updatedTime;

    private Integer status;
}
