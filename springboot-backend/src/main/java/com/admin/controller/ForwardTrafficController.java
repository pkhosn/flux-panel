package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardTrafficController {
    private final JdbcTemplate jdbc;

    public ForwardTrafficController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/stats")
    public R stats(@RequestBody Map<String, Object> input) {
        Long forwardId = id(input.get("forwardId"));
        int owner = JwtUtil.getUserIdFromToken();
        boolean admin = JwtUtil.getRoleIdFromToken() == 0;
        if (input.get("forwardId") != null && forwardId == null) return R.err("规则 ID 无效");
        if (forwardId != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM forward WHERE id=? AND (? OR user_id=?)",
                    Integer.class, forwardId, admin, owner);
            if (count == null || count != 1) return R.err("规则不存在或无权查看");
        }
        long since = (System.currentTimeMillis() / 3600000L - 23) * 3600000L;
        List<Map<String, Object>> rows;
        if (forwardId != null) {
            rows = jdbc.queryForList(
                    "SELECT hour_start AS hourStart, SUM(bytes) AS bytes FROM forward_flow_hour " +
                            "WHERE forward_id=? AND hour_start>=? AND (? OR user_id=?) " +
                            "GROUP BY hour_start ORDER BY hour_start",
                    forwardId, since, admin, owner);
        } else if (admin) {
            rows = jdbc.queryForList(
                    "SELECT hour_start AS hourStart, SUM(bytes) AS bytes FROM forward_flow_hour " +
                            "WHERE hour_start>=? GROUP BY hour_start ORDER BY hour_start", since);
        } else {
            rows = jdbc.queryForList(
                    "SELECT hour_start AS hourStart, SUM(bytes) AS bytes FROM forward_flow_hour " +
                            "WHERE user_id=? AND hour_start>=? GROUP BY hour_start ORDER BY hour_start",
                    owner, since);
        }
        return R.ok(rows);
    }

    @PostMapping("/clear-traffic")
    @Transactional
    public R clear(@RequestBody Map<String, Object> input) {
        Long forwardId = id(input.get("id"));
        if (forwardId == null) return R.err("规则 ID 无效");
        int changed = jdbc.update(
                "UPDATE forward SET traffic_baseline=in_flow+out_flow WHERE id=? AND (? OR user_id=?)",
                forwardId, JwtUtil.getRoleIdFromToken() == 0, JwtUtil.getUserIdFromToken());
        return changed == 1 ? R.ok() : R.err("规则不存在或无权操作");
    }

    private static Long id(Object value) {
        if (value == null) return null;
        try {
            long id = Long.parseLong(String.valueOf(value));
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
