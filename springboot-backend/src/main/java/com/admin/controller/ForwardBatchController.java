package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.service.ForwardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardBatchController {
    private final JdbcTemplate jdbc;
    private final ForwardService forwardService;

    public ForwardBatchController(JdbcTemplate jdbc, ForwardService forwardService) {
        this.jdbc = jdbc;
        this.forwardService = forwardService;
    }

    @PostMapping("/batch")
    public R batch(@RequestBody Map<String, Object> input) {
        Object rawIds = input.get("ids");
        if (!(rawIds instanceof List<?> values) || values.isEmpty() || values.size() > 500) {
            return R.err("请选择 1–500 条规则");
        }
        Set<Long> ids = new HashSet<>();
        for (Object value : values) {
            Long id = positiveId(value);
            if (id == null || !ids.add(id)) return R.err("规则 ID 无效或重复");
        }
        int owner = JwtUtil.getUserIdFromToken();
        boolean admin = JwtUtil.getRoleIdFromToken() == 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.toArray();
        String scope = "id IN (" + placeholders + ") AND (? OR user_id=?)";
        Object[] scopedArgs = java.util.Arrays.copyOf(args, args.length + 2);
        scopedArgs[args.length] = admin;
        scopedArgs[args.length + 1] = owner;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM forward WHERE " + scope,
                Integer.class, scopedArgs);
        if (count == null || count != ids.size()) return R.err("选择中包含不存在或无权操作的规则");

        String action = String.valueOf(input.get("action"));
        int changed;
        switch (action) {
            case "group" -> {
                Long groupId = input.get("groupId") == null ? 0L : nonnegativeId(input.get("groupId"));
                if (groupId == null) return R.err("分组 ID 无效");
                if (groupId != 0) {
                    Integer matched = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM forward f JOIN forward_group g " +
                                    "ON g.id=? AND g.user_id=f.user_id WHERE " +
                                    scope.replace("id IN", "f.id IN").replace("user_id=?", "f.user_id=?"),
                            Integer.class, join(groupId, scopedArgs));
                    if (matched == null || matched != ids.size()) return R.err("规则和分组必须属于同一用户");
                }
                changed = jdbc.update("UPDATE forward SET group_id=? WHERE " + scope,
                        join(groupId == 0 ? null : groupId, scopedArgs));
            }
            case "clear" -> changed = jdbc.update(
                    "UPDATE forward SET traffic_baseline=in_flow+out_flow WHERE " + scope, scopedArgs);
            case "delete" -> {
                List<Long> selected = jdbc.queryForList("SELECT id FROM forward WHERE " + scope + " ORDER BY id", Long.class, scopedArgs);
                for (Long id : selected) {
                    R result = forwardService.deleteForward(id);
                    if (result.getCode() != 0) return R.err("规则 " + id + " 删除失败：" + result.getMsg());
                }
                return R.ok(Map.of("count", selected.size()));
            }
            case "enable", "disable" -> {
                int targetStatus = "enable".equals(action) ? 1 : 0;
                List<Map<String, Object>> selected = jdbc.queryForList(
                        "SELECT id, status FROM forward WHERE " + scope + " ORDER BY id", scopedArgs);
                List<Long> completed = new ArrayList<>();
                for (Map<String, Object> row : selected) {
                    if (((Number) row.get("status")).intValue() == targetStatus) continue;
                    Long id = ((Number) row.get("id")).longValue();
                    R result = targetStatus == 1 ? forwardService.resumeForward(id) : forwardService.pauseForward(id);
                    if (result.getCode() != 0) {
                        completed.sort(Comparator.reverseOrder());
                        List<Long> rollbackFailed = new ArrayList<>();
                        for (Long completedId : completed) {
                            R rollback = targetStatus == 1
                                    ? forwardService.pauseForward(completedId) : forwardService.resumeForward(completedId);
                            if (rollback.getCode() != 0) rollbackFailed.add(completedId);
                        }
                        return R.err("规则 " + id + " 操作失败：" + result.getMsg()
                                + (rollbackFailed.isEmpty() ? "；已回退此前成功的规则"
                                : "；回退失败的规则 ID：" + rollbackFailed));
                    }
                    completed.add(id);
                }
                return R.ok(Map.of("count", completed.size()));
            }
            case "switch" -> {
                Long tunnelId = positiveId(input.get("tunnelId"));
                if (tunnelId == null || tunnelId > Integer.MAX_VALUE) return R.err("目标隧道 ID 无效");
                List<Map<String, Object>> selected = jdbc.queryForList(
                        "SELECT id, user_id, name, tunnel_id, remote_addr, strategy, protocol, " +
                                "proxy_accept, proxy_send, proxy_trusted_cidrs, origin_tls, origin_server_name, origin_root_ca, origin_alpn, group_id, status " +
                                "FROM forward WHERE " + scope + " ORDER BY id", scopedArgs);
                List<Map<String, Object>> failures = new ArrayList<>();
                List<Long> succeeded = new ArrayList<>();
                for (Map<String, Object> row : selected) {
                    long id = ((Number) row.get("id")).longValue();
                    Integer originalPort = jdbc.queryForObject(
                            "SELECT MIN(port) FROM forward_port WHERE forward_id=?", Integer.class, id);
                    String error = null;
                    if (((Number) row.get("tunnel_id")).intValue() == tunnelId.intValue()) {
                        succeeded.add(id);
                        continue;
                    }
                    Integer distinctPorts = jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT port) FROM forward_port WHERE forward_id=?", Integer.class, id);
                    Integer shared = jdbc.queryForObject("SELECT COUNT(*) FROM forward WHERE id=? AND tls_inbound_id>0", Integer.class, id);
                    if (((Number) row.get("status")).intValue() != 1) error = "暂停中的规则请先恢复";
                    else if (shared != null && shared > 0) error = "共享 TLS 规则请逐条编辑并重新选择目标入口";
                    else if (originalPort == null || distinctPorts == null || distinctPorts != 1) error = "原入口端口不一致，无法保持原端口切换";
                    else {
                        int ruleOwner = ((Number) row.get("user_id")).intValue();
                        Integer allowed = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM tunnel t WHERE t.id=? AND t.status=1 " +
                                        "AND EXISTS (SELECT 1 FROM user_tunnel ut " +
                                        "WHERE ut.tunnel_id=t.id AND ut.user_id=? AND ut.status=1)",
                                Integer.class, tunnelId, ruleOwner);
                        if (allowed == null || allowed != 1) error = "无目标隧道权限";
                    }
                    if (error == null) {
                        ForwardUpdateDto update = forwardUpdate(row, tunnelId.intValue());
                        update.setInPort(originalPort);
                        try {
                            R result = forwardService.updateForward(update);
                            if (result.getCode() != 0) error = result.getMsg();
                        } catch (RuntimeException ex) {
                            error = ex.getMessage() == null ? "切换失败，请检查节点状态" : ex.getMessage();
                        }
                    }
                    if (error == null) succeeded.add(id);
                    else failures.add(Map.of("id", id, "name", String.valueOf(row.get("name")),
                            "port", originalPort == null ? 0 : originalPort, "reason", error));
                }
                return R.ok(Map.of("count", succeeded.size(), "succeededIds", succeeded, "failures", failures));
            }
            default -> {
                return R.err("此批量操作尚未开放");
            }
        }
        return R.ok(Map.of("count", changed));
    }

    private static Object[] join(Object first, Object[] rest) {
        Object[] result = new Object[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private static ForwardUpdateDto forwardUpdate(Map<String, Object> row, int tunnelId) {
        ForwardUpdateDto update = new ForwardUpdateDto();
        update.setId(((Number) row.get("id")).longValue());
        update.setUserId(((Number) row.get("user_id")).intValue());
        update.setName((String) row.get("name"));
        update.setTunnelId(tunnelId);
        update.setRemoteAddr((String) row.get("remote_addr"));
        update.setStrategy((String) row.get("strategy"));
        update.setProtocol((String) row.get("protocol"));
        update.setProxyAccept(((Number) row.get("proxy_accept")).intValue());
        update.setProxySend(((Number) row.get("proxy_send")).intValue());
        update.setOriginTls(((Number) row.get("origin_tls")).intValue());
        update.setOriginServerName((String) row.get("origin_server_name"));
        update.setOriginRootCa((String) row.get("origin_root_ca"));
        update.setOriginAlpn((String) row.get("origin_alpn"));
        update.setProxyTrustedCidrs((String) row.get("proxy_trusted_cidrs"));
        Number group = (Number) row.get("group_id");
        update.setGroupId(group == null ? 0 : group.intValue());
        return update;
    }

    private static Long positiveId(Object value) {
        Long id = nonnegativeId(value);
        return id != null && id > 0 ? id : null;
    }

    private static Long nonnegativeId(Object value) {
        try {
            long id = Long.parseLong(String.valueOf(value));
            return id >= 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
