package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward/group")
public class ForwardGroupController {
    private final JdbcTemplate jdbc;

    public ForwardGroupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/list")
    public R list() {
        int userId = JwtUtil.getUserIdFromToken();
        if (JwtUtil.getRoleIdFromToken() == 0) {
            return R.ok(jdbc.queryForList("SELECT g.id, g.user_id AS userId, g.name, " +
                    "(SELECT COUNT(*) FROM forward f WHERE f.group_id=g.id) AS ruleCount " +
                    "FROM forward_group g ORDER BY g.user_id, g.id"));
        }
        return R.ok(jdbc.queryForList("SELECT g.id, g.user_id AS userId, g.name, " +
                "(SELECT COUNT(*) FROM forward f WHERE f.group_id=g.id) AS ruleCount " +
                "FROM forward_group g WHERE g.user_id=? ORDER BY g.id", userId));
    }

    @PostMapping("/create")
    public R create(@RequestBody Map<String, Object> input) {
        String name = name(input);
        if (name == null) return R.err("分组名称须为 1–64 个字符");
        int owner = JwtUtil.getUserIdFromToken();
        try {
            jdbc.update("INSERT INTO forward_group(user_id,name) VALUES (?,?)", owner, name);
            return R.ok();
        } catch (DataIntegrityViolationException ex) {
            return R.err("分组名称已存在");
        }
    }

    @PostMapping("/update")
    public R update(@RequestBody Map<String, Object> input) {
        Long id = id(input);
        String name = name(input);
        if (id == null || name == null) return R.err("分组参数无效");
        try {
            int changed = jdbc.update("UPDATE forward_group SET name=? WHERE id=? AND user_id=?",
                    name, id, JwtUtil.getUserIdFromToken());
            return changed == 1 ? R.ok() : R.err("分组不存在或无权操作");
        } catch (DataIntegrityViolationException ex) {
            return R.err("分组名称已存在");
        }
    }

    @PostMapping("/delete")
    @Transactional
    public R delete(@RequestBody Map<String, Object> input) {
        Long id = id(input);
        if (id == null) return R.err("分组 ID 无效");
        int owner = JwtUtil.getUserIdFromToken();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT id FROM forward_group WHERE id=? AND user_id=?", id, owner);
        if (found.isEmpty()) return R.err("分组不存在或无权操作");
        jdbc.update("UPDATE forward SET group_id=NULL WHERE group_id=? AND user_id=?", id, owner);
        jdbc.update("DELETE FROM forward_group WHERE id=? AND user_id=?", id, owner);
        return R.ok();
    }

    private static Long id(Map<String, Object> input) {
        try {
            long value = Long.parseLong(String.valueOf(input.get("id")));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String name(Map<String, Object> input) {
        Object value = input.get("name");
        if (!(value instanceof String)) return null;
        String name = ((String) value).trim();
        return name.isEmpty() || name.length() > 64 ? null : name;
    }
}
