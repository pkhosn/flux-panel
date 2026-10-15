package com.admin.entity;

import java.io.Serializable;
import java.util.List;

import com.admin.common.dto.ForwardPortDto;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(autoResultMap = true)
public class Forward extends BaseEntity{

    private static final long serialVersionUID = 1L;

    private Integer userId;

    private String userName;

    private String name;

    private Integer tunnelId;

    private String remoteAddr;

    private String strategy;

    private String protocol;

    private Integer proxyAccept;

    private Integer proxySend;

    private String proxyTrustedCidrs;

    private Integer originTls;
    private String originServerName;
    private String originRootCa;
    private String originAlpn;

    private Integer groupId;

    private Long trafficBaseline;

    private Long inFlow;

    private Long outFlow;

    private Integer inx;
}
