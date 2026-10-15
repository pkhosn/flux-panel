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
    @Transactional
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
                        "SELECT id, user_id, name, tunnel_id, remote_addr, strategy, protocol, group_id, status " +
                                "FROM forward WHERE " + scope + " ORDER BY id", scopedArgs);
                if (selected.stream().anyMatch(row -> ((Number) row.get("status")).intValue() != 1)) {
                    return R.err("暂停中的规则请先恢复，再批量切换入口");
                }
                for (Map<String, Object> row : selected) {
                    int ruleOwner = ((Number) row.get("user_id")).intValue();
                    if (!admin && ruleOwner != owner) return R.err("规则无权操作");
                    Number originalPort = jdbc.queryForObject(
                            "SELECT MIN(port) FROM forward_port WHERE forward_id=?", Number.class, row.get("id"));
                    Integer distinctPorts = jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT port) FROM forward_port WHERE forward_id=?", Integer.class, row.get("id"));
                    if (originalPort == null || distinctPorts == null || distinctPorts != 1) {
                        return R.err("规则 " + row.get("id") + " 的原入口端口不一致，无法安全批量切换");
                    }
                    row.put("original_port", originalPort.intValue());
                    Integer allowed = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM tunnel t WHERE t.id=? AND t.status=1 " +
                                    "AND EXISTS (SELECT 1 FROM user_tunnel ut " +
                                    "WHERE ut.tunnel_id=t.id AND ut.user_id=? AND ut.status=1)",
                            Integer.class, tunnelId, ruleOwner);
                    if (allowed == null || allowed != 1) return R.err("规则 " + row.get("id") + " 无目标隧道权限");
                }
                List<Map<String, Object>> completed = new ArrayList<>();
                for (Map<String, Object> row : selected) {
                    if (((Number) row.get("tunnel_id")).intValue() == tunnelId.intValue()) continue;
                    ForwardUpdateDto update = forwardUpdate(row, tunnelId.intValue());
                    R result = forwardService.updateForward(update);
                    if (result.getCode() != 0) {
                        List<Long> rollbackFailed = new ArrayList<>();
                        for (int i = completed.size() - 1; i >= 0; i--) {
                            Map<String, Object> old = completed.get(i);
                            ForwardUpdateDto restore = forwardUpdate(old, ((Number) old.get("tunnel_id")).intValue());
                            restore.setInPort(((Number) old.get("original_port")).intValue());
                            R rollback = forwardService.updateForward(restore);
                            if (rollback.getCode() != 0) rollbackFailed.add(((Number) old.get("id")).longValue());
                        }
                        return R.err("规则 " + row.get("id") + " 切换失败：" + result.getMsg()
                                + (rollbackFailed.isEmpty() ? "；已尝试恢复此前规则"
                                : "；恢复失败的规则 ID：" + rollbackFailed));
                    }
                    completed.add(row);
                }
                return R.ok(Map.of("count", completed.size()));
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
