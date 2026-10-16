package com.admin.common.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Data
public class NodeDto {

    @NotBlank(message = "节点名称不能为空")
    private String name;

    @javax.validation.constraints.Size(max = 64, message = "节点分组最长64个字符")
    private String groupName;

    private String serverIp;

    @NotBlank(message = "可用端口不能为空")
    private String port;

    private String interfaceName;

    private String tcpListenAddr = "0.0.0.0";

    private String udpListenAddr = "0.0.0.0";

} 
