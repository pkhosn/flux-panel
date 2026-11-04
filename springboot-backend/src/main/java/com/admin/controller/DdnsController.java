package com.admin.controller;

import com.admin.common.utils.JwtUtil;
import com.admin.common.lang.R;
import com.admin.service.ddns.DdnsService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.function.Supplier;

/** No LogAnnotation: API tokens must never enter request/response logs. */
@RestController
@RequestMapping("/api/v1/ddns")
public class DdnsController {
    private final DdnsService service;
    public DdnsController(DdnsService service) { this.service = service; }
    private long owner() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null)
            throw new IllegalStateException("请先登录");
        Integer user = JwtUtil.getUserIdFromToken();
        if (user == null || user <= 0) throw new IllegalStateException("请先登录");
        return user;
    }
    private int interval(Map<String,Object> p) {
        if (!p.containsKey("intervalMinutes")) return 5;
        try { return Integer.parseInt(String.valueOf(p.get("intervalMinutes"))); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("同步间隔无效"); }
    }
    private R result(Supplier<Object> action) {
        try { return R.ok(action.get()); }
        catch (IllegalArgumentException | IllegalStateException ex) { return R.err(ex.getMessage()); }
    }
    private String str(Map<String,Object> p, String key) { return p.get(key) instanceof String s ? s : ""; }
    private long id(Map<String,Object> p) {
        try { long id = Long.parseLong(String.valueOf(p.get("id"))); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("任务 ID 无效");
    }
    @PostMapping("/list") public R list() { return result(() -> service.list(owner())); }
    @PostMapping("/account") public R account(@RequestBody Map<String,Object> p) {
        return result(() -> { service.saveAccount(owner(), str(p,"zoneId"), str(p,"token")); return null; });
    }
    @PostMapping("/records") public R records() { return result(() -> service.records(owner())); }
    @PostMapping("/save") public R save(@RequestBody Map<String,Object> p) {
        return result(() -> { service.saveTask(owner(), p.get("id") == null ? null : id(p), str(p,"name"), str(p,"source"), str(p,"recordId"), Boolean.TRUE.equals(p.get("enabled")), interval(p)); return null; });
    }
    @PostMapping("/enable") public R enable(@RequestBody Map<String,Object> p) {
        return result(() -> { service.enable(owner(), id(p), Boolean.TRUE.equals(p.get("enabled"))); return null; });
    }
    @PostMapping("/delete") public R delete(@RequestBody Map<String,Object> p) {
        return result(() -> { service.delete(owner(), id(p)); return null; });
    }
    @PostMapping("/sync") public R sync(@RequestBody Map<String,Object> p) {
        return result(() -> { service.sync(owner(), id(p)); return Map.of("queued", true); });
    }
}
