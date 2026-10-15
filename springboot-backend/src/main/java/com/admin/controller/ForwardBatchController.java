package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardBatchController {
    private final JdbcTemplate jdbc;

    public ForwardBatchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
