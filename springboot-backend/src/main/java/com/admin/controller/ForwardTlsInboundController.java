package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.ForwardSni;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.GostUtil;
import com.alibaba.fastjson.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Resource;
import com.admin.service.ForwardService;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward/tls-inbound")
public class ForwardTlsInboundController {
    @Resource
    private ForwardService forwardService;
    private final JdbcTemplate jdbc;
    public ForwardTlsInboundController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/list")
    public R list() {
        if (JwtUtil.getRoleIdFromToken() != 0) {
            return R.ok(jdbc.queryForList("SELECT i.id,i.tunnel_id AS tunnelId,i.name,i.port,i.allowed_sni AS allowedSni,i.enabled,i.policy " +
                    "FROM forward_tls_inbound i JOIN user_tunnel u ON u.tunnel_id=i.tunnel_id WHERE u.user_id=? AND u.status=1 ORDER BY i.id",
                    JwtUtil.getUserIdFromToken()));
        }
        return R.ok(jdbc.queryForList("SELECT id,tunnel_id AS tunnelId,name,port,allowed_sni AS allowedSni,enabled,policy FROM forward_tls_inbound ORDER BY id"));
    }

    @PostMapping("/create")
    public synchronized R create(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            int tunnelId = Integer.parseInt(String.valueOf(input.get("tunnelId")));
            int port = Integer.parseInt(String.valueOf(input.get("port")));
            String name = String.valueOf(input.getOrDefault("name", "")).trim();
            String allowed = ForwardSni.normalize(String.valueOf(input.getOrDefault("allowedSni", "")), true);
            if (name.isEmpty() || name.length() > 64 || port < 1 || port > 65535) return R.err("入口名称或端口无效");
            var nodes = jdbc.queryForList("SELECT node_id FROM chain_tunnel WHERE tunnel_id=? AND chain_type=1", tunnelId);
            if (nodes.isEmpty()) return R.err("隧道没有入口节点");
            for (var node : nodes) {
                Object id = node.get("node_id");
                Integer used = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM forward_port WHERE node_id=? AND port=?) + " +
                        "(SELECT COUNT(*) FROM chain_tunnel WHERE node_id=? AND port=?) + " +
                        "(SELECT COUNT(*) FROM forward_tls_inbound i JOIN chain_tunnel c ON c.tunnel_id=i.tunnel_id " +
                        "WHERE c.chain_type=1 AND c.node_id=? AND i.port=?)", Integer.class, id, port, id, port, id, port);
                if (used != null && used > 0) return R.err("端口已被规则、隧道或共享入口占用");
            }
            jdbc.update("INSERT INTO forward_tls_inbound(tunnel_id,name,port,allowed_sni) VALUES (?,?,?,?)", tunnelId, name, port, allowed);
            return R.ok();
        } catch (IllegalArgumentException ex) { return R.err("入口参数或 SNI 域名无效"); }
    }

    @PostMapping("/update")
    public synchronized R update(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            long id = Long.parseLong(String.valueOf(input.get("id")));
            String name = String.valueOf(input.getOrDefault("name", "")).trim();
            String allowed = ForwardSni.normalize(String.valueOf(input.getOrDefault("allowedSni", "")), true);
            if (name.isEmpty() || name.length() > 64) return R.err("入口名称须为 1–64 个字符");
            for (String existing : jdbc.queryForList("SELECT sni_names FROM forward WHERE tls_inbound_id=?", String.class, id)) {
                for (String domain : existing.split(",")) {
                    if (!ForwardSni.allows(allowed, domain)) return R.err("已有规则域名超出新范围：" + domain);
                }
            }
            var previous = jdbc.queryForList("SELECT name,allowed_sni,enabled FROM forward_tls_inbound WHERE id=?", id);
            if (previous.size() != 1) return R.err("入口不存在");
            boolean enabled = ((Number) previous.getFirst().get("enabled")).intValue() == 1;
            if (enabled) {
                R stopped = toggle(Map.of("id", id, "enabled", 0));
                if (stopped.getCode() != 0) return stopped;
            }
            jdbc.update("UPDATE forward_tls_inbound SET name=?,allowed_sni=? WHERE id=?", name, allowed, id);
            if (enabled) {
                R started = toggle(Map.of("id", id, "enabled", 1));
                if (started.getCode() != 0) {
                    jdbc.update("UPDATE forward_tls_inbound SET name=?,allowed_sni=? WHERE id=?", previous.getFirst().get("name"), previous.getFirst().get("allowed_sni"), id);
                    R restored = toggle(Map.of("id", id, "enabled", 1));
                    return R.err(started.getMsg() + (restored.getCode() == 0 ? "；已恢复原配置" : "；恢复失败：" + restored.getMsg()));
                }
            }
            return R.ok();
        } catch (IllegalArgumentException ex) { return R.err("入口参数或域名范围无效"); }
    }

    @PostMapping("/policy")
    public synchronized R policy(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            long id = Long.parseLong(String.valueOf(input.get("id")));
            JSONObject policy = new JSONObject();
            int proxy = Integer.parseInt(String.valueOf(input.getOrDefault("proxyAccept", 0)));
            String trusted = String.valueOf(input.getOrDefault("trustedCidrs", ""));
            String invalid = GostUtil.validateForwardProxy("tcp", proxy, 0, trusted);
            if (invalid != null) return R.err(invalid);
            int limit = Integer.parseInt(String.valueOf(input.getOrDefault("maxHandshakes", 128)));
            int timeout = Integer.parseInt(String.valueOf(input.getOrDefault("timeoutSeconds", 5)));
            long defaultRule = Long.parseLong(String.valueOf(input.getOrDefault("defaultRuleId", 0)));
            boolean rejectEmpty = !Boolean.FALSE.equals(input.get("rejectEmpty"));
            boolean fallback = Boolean.TRUE.equals(input.get("unknownDefault"));
            if (limit < 1 || limit > 4096 || timeout < 1 || timeout > 60) return R.err("并发须为 1–4096，超时须为 1–60 秒");
            var rows = jdbc.queryForList("SELECT policy,enabled FROM forward_tls_inbound WHERE id=?", id);
            var versions = jdbc.queryForList("SELECT n.version FROM node n JOIN chain_tunnel c ON c.node_id=n.id " +
                    "JOIN forward_tls_inbound i ON i.tunnel_id=c.tunnel_id WHERE i.id=? AND c.chain_type=1", String.class, id);
            for (String version : versions) {
                com.admin.entity.Node node = new com.admin.entity.Node(); node.setVersion(version);
                com.admin.entity.Forward rule = new com.admin.entity.Forward(); rule.setTlsInboundId((int) id);
                if (!GostUtil.supportsForwardProxy(node, rule)) return R.err("请先升级入口 Agent 至 2.0.15-beta，再修改高级策略");
            }
            if (rows.size() != 1) return R.err("入口不存在");
            if (defaultRule != 0) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM forward f JOIN user u ON u.id=f.user_id " +
                        "WHERE f.id=? AND f.tls_inbound_id=? AND u.role_id=0", Integer.class, defaultRule, id);
                if (!Integer.valueOf(1).equals(count)) return R.err("默认规则必须是此入口下管理员的规则");
            }
            if ((fallback || !rejectEmpty) && defaultRule == 0) return R.err("请先选择管理员默认规则");
            policy.put("sniProxy", proxy);
            policy.put("sniTrustedCIDRs", trusted.isBlank() ? List.of() : java.util.Arrays.stream(trusted.split(",")).map(String::trim).toList());
            policy.put("sniRejectEmpty", rejectEmpty);
            policy.put("sniUnknownDefault", fallback);
            policy.put("sniMaxHandshakes", limit);
            policy.put("sniTimeout", timeout + "s");
            policy.put("defaultRuleId", defaultRule);
            boolean enabled = ((Number) rows.getFirst().get("enabled")).intValue() == 1;
            if (enabled) {
                R stopped = toggle(Map.of("id", id, "enabled", 0));
                if (stopped.getCode() != 0) return stopped;
            }
            String previous = (String) rows.getFirst().get("policy");
            jdbc.update("UPDATE forward_tls_inbound SET policy=? WHERE id=?", policy.toJSONString(), id);
            if (enabled) {
                R started = toggle(Map.of("id", id, "enabled", 1));
                if (started.getCode() != 0) {
                    jdbc.update("UPDATE forward_tls_inbound SET policy=? WHERE id=?", previous, id);
                    R restored = toggle(Map.of("id", id, "enabled", 1));
                    return R.err(started.getMsg() + (restored.getCode() == 0 ? "；已恢复原策略" : "；原策略恢复失败：" + restored.getMsg()));
                }
            }
            return R.ok();
        } catch (IllegalArgumentException ex) { return R.err("入口策略参数无效"); }
    }

    @PostMapping("/node-policy")
    public synchronized R nodePolicy(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理节点 TLS 策略");
        try {
            long nodeId = Long.parseLong(String.valueOf(input.get("nodeId")));
            int only = Integer.parseInt(String.valueOf(input.get("tlsOnly")));
            if (only != 0 && only != 1) return R.err("节点策略无效");
            if (only == 1) {
                Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM forward f JOIN forward_port p ON p.forward_id=f.id " +
                        "WHERE p.node_id=? AND COALESCE(f.tls_inbound_id,0)=0 AND f.protocol<>'udp'", Integer.class, nodeId);
                if (existing != null && existing > 0) return R.err("请先删除或迁移该节点的普通 TCP 规则（包括暂停规则），再开启仅 TLS 模式");
            }
            return jdbc.update("UPDATE node SET tls_only=? WHERE id=?", only, nodeId) == 1 ? R.ok() : R.err("节点不存在");
        } catch (NumberFormatException ex) { return R.err("节点参数无效"); }
    }

    @PostMapping("/toggle")
    public synchronized R toggle(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            long id = Long.parseLong(String.valueOf(input.get("id")));
            int target = Integer.parseInt(String.valueOf(input.get("enabled")));
            if (target != 0 && target != 1) return R.err("入口状态无效");
            var rows = jdbc.queryForList("SELECT enabled,suspended_ids FROM forward_tls_inbound WHERE id=?", id);
            if (rows.size() != 1) return R.err("入口不存在");
            int previous = ((Number) rows.getFirst().get("enabled")).intValue();
            if (previous == target) return R.ok();
            List<Long> rules = new ArrayList<>();
            if (target == 0) rules.addAll(jdbc.queryForList("SELECT id FROM forward WHERE tls_inbound_id=? AND status=1", Long.class, id));
            else {
                for (String value : ((String) rows.getFirst().get("suspended_ids")).split(",")) {
                    if (!value.isBlank()) rules.addAll(jdbc.queryForList("SELECT id FROM forward WHERE id=? AND tls_inbound_id=? AND status=0", Long.class, Long.parseLong(value), id));
                }
            }
            // Enable first so ordinary resume authorization accepts this entry.
            if (target == 1) jdbc.update("UPDATE forward_tls_inbound SET enabled=1 WHERE id=?", id);
            List<Long> completed = new ArrayList<>();
            for (Long rule : rules) {
                R result;
                try { result = target == 1 ? forwardService.resumeForward(rule) : forwardService.pauseForward(rule); }
                catch (RuntimeException ex) { result = R.err("规则 " + rule + " 操作异常，请检查节点"); }
                if (result.getCode() != 0) {
                    Collections.reverse(completed);
                    List<Long> failed = new ArrayList<>();
                    for (Long changed : completed) {
                        try {
                            R restored = target == 1 ? forwardService.pauseForward(changed) : forwardService.resumeForward(changed);
                            if (restored.getCode() != 0) failed.add(changed);
                        } catch (RuntimeException ex) { failed.add(changed); }
                    }
                    jdbc.update("UPDATE forward_tls_inbound SET enabled=? WHERE id=?", previous, id);
                    return R.err(result.getMsg() + (failed.isEmpty() ? "；已尝试回退" : "；回退失败规则：" + failed));
                }
                completed.add(rule);
            }
            String suspended = target == 0 ? rules.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) : "";
            jdbc.update("UPDATE forward_tls_inbound SET enabled=?,suspended_ids=? WHERE id=?", target, suspended, id);
            return R.ok();
        } catch (NumberFormatException ex) { return R.err("入口参数无效"); }
    }

    @PostMapping("/delete")
    public synchronized R delete(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            long id = Long.parseLong(String.valueOf(input.get("id")));
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM forward WHERE tls_inbound_id=?", Integer.class, id);
            if (count != null && count > 0) return R.err("请先移除引用此入口的规则（包括暂停规则）");
            return jdbc.update("DELETE FROM forward_tls_inbound WHERE id=?", id) == 1 ? R.ok() : R.err("入口不存在");
        } catch (NumberFormatException ex) { return R.err("入口 ID 无效"); }
    }
}
