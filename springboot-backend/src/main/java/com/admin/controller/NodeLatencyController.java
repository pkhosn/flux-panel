package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.service.NodeLatencyService;
import com.admin.service.NodeService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/node")
public class NodeLatencyController {
    private final NodeService nodes;
    private final NodeLatencyService latency;
    public NodeLatencyController(NodeService nodes, NodeLatencyService latency) { this.nodes = nodes; this.latency = latency; }
    @PostMapping("/latency")
    public R latency(@RequestBody Map<String,Object> input) {
        long id;
        try { id = Long.parseLong(String.valueOf(input.get("id"))); }
        catch (NumberFormatException ex) { return R.err("节点 ID 无效"); }
        R visible = nodes.getAllNodes();
        if (visible.getCode() != 0) return visible;
        Node node = ((List<Node>) visible.getData()).stream().filter(n -> n.getId() == id).findFirst().orElse(null);
        if (node == null) return R.err("节点不存在或无权访问");
        String target = node.getDetectedIpv4();
        if (target == null || target.isBlank()) target = node.getServerIp();
        if (target == null || target.isBlank()) target = node.getDetectedIpv6();
        if (target == null || target.isBlank()) return R.err("节点尚未获取地址");
        target = target.trim();
        if (!target.matches("[A-Za-z0-9.:_-]{1,253}")) return R.err("节点地址不支持探测");
        return R.ok(latency.measure(target, Boolean.TRUE.equals(input.get("run"))));
    }
}
