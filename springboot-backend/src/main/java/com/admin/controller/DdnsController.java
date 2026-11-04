package com.admin.controller;

import com.admin.common.annotation.RequireRole;
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
    @RequireRole @PostMapping("/list") public R list() { return result(service::list); }
    @RequireRole @PostMapping("/account") public R account(@RequestBody Map<String,Object> p) {
        return result(() -> { service.saveAccount(str(p,"zoneId"), str(p,"token")); return null; });
    }
    @RequireRole @PostMapping("/records") public R records() { return result(service::records); }
    @RequireRole @PostMapping("/save") public R save(@RequestBody Map<String,Object> p) {
        return result(() -> { service.saveTask(p.get("id") == null ? null : id(p), str(p,"name"), str(p,"source"), str(p,"recordId"), Boolean.TRUE.equals(p.get("enabled"))); return null; });
    }
    @RequireRole @PostMapping("/enable") public R enable(@RequestBody Map<String,Object> p) {
        return result(() -> { service.enable(id(p), Boolean.TRUE.equals(p.get("enabled"))); return null; });
    }
    @RequireRole @PostMapping("/delete") public R delete(@RequestBody Map<String,Object> p) {
        return result(() -> { service.delete(id(p)); return null; });
    }
    @RequireRole @PostMapping("/sync") public R sync(@RequestBody Map<String,Object> p) {
        return result(() -> { service.sync(id(p)); return Map.of("queued", true); });
    }
}
