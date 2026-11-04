package com.admin.service.ddns;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.IDN;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;

@Service
public class DdnsService {
    private final JdbcTemplate jdbc;
    private final DdnsRemote remote;
    private final DdnsCrypto crypto;
    // All operations share a lock: configuration edits cannot race with a DNS update.
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ddns-sync"); t.setDaemon(true); return t;
    });
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean scheduled;

    public DdnsService(JdbcTemplate jdbc, DdnsRemote remote, DdnsCrypto crypto) {
        this.jdbc = jdbc; this.remote = remote; this.crypto = crypto;
    }
    private Map<String,Object> account() {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT zone_id,token_cipher FROM ddns_account WHERE id=1");
        if (rows.isEmpty()) throw new IllegalStateException("请先保存 Cloudflare 凭证");
        return rows.get(0);
    }
    private String token(Map<String,Object> account) { return crypto.decrypt((String)account.get("token_cipher")); }
    public Map<String,Object> list() {
        List<Map<String,Object>> accounts = jdbc.queryForList("SELECT zone_id FROM ddns_account WHERE id=1");
        List<Map<String,Object>> tasks = jdbc.queryForList("SELECT * FROM ddns_task ORDER BY id");
        for (Map<String,Object> task : tasks) task.put("running", pending.contains(((Number)task.get("id")).longValue()));
        return Map.of("configured", !accounts.isEmpty(), "zoneId", accounts.isEmpty() ? "" : accounts.get(0).get("zone_id"), "tasks", tasks);
    }
    private void enter() {
        if (!lock.tryLock()) throw new IllegalStateException("正在同步或保存配置，请稍后重试");
    }
    public void saveAccount(String zone, String rawToken) {
        zone = DdnsRemote.id(zone);
        enter();
        try {
            String value = rawToken == null ? "" : rawToken.trim();
            if (value.isEmpty()) value = token(account());
            if (!value.matches("[A-Za-z0-9_-]{20,256}")) throw new IllegalArgumentException("API Token 格式无效");
            remote.zone(zone, value);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE zone_id<>?", Integer.class, zone) > 0)
                throw new IllegalArgumentException("请先删除旧 Zone 的同步任务再切换 Zone");
            jdbc.update("INSERT INTO ddns_account(id,zone_id,token_cipher) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET zone_id=excluded.zone_id,token_cipher=excluded.token_cipher", zone, crypto.encrypt(value));
        } finally { lock.unlock(); }
    }
    public List<Map<String,Object>> records() {
        Map<String,Object> a = account(); return remote.records((String)a.get("zone_id"), token(a));
    }
    public static String hostname(String value) {
        String host;
        try { host = IDN.toASCII(value == null ? "" : value.trim()).toLowerCase(Locale.ROOT); }
        catch (Exception ex) { throw new IllegalArgumentException("请输入有效的来源域名"); }
        if (host.endsWith(".")) host = host.substring(0, host.length()-1);
        if (host.length() > 253 || !host.contains(".") || host.matches("[0-9.]+")) throw new IllegalArgumentException("请输入来源 DDNS 域名，不含端口或路径");
        for (String label : host.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IllegalArgumentException("来源域名格式无效");
        return host;
    }
    private Map<String,Object> task(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM ddns_task WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("同步任务不存在");
        return rows.get(0);
    }
    private void checkRecord(JsonNode record, String expectedName) {
        if (!"A".equals(record.path("type").asText()) || record.path("proxied").asBoolean()
                || (!expectedName.isEmpty() && !expectedName.equals(record.path("name").asText())))
            throw new IllegalArgumentException("目标记录名称或类型已改变，或已启用代理；请重新绑定仅 DNS 的 A 记录");
    }
    public void saveTask(Long id, String name, String source, String recordId, boolean enabled) {
        if (name == null || name.isBlank() || name.trim().length() > 64) throw new IllegalArgumentException("名称须为 1–64 个字符");
        source = hostname(source); recordId = DdnsRemote.id(recordId);
        enter();
        try {
            if (id != null) task(id);
            else if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task", Integer.class) >= 50) throw new IllegalArgumentException("最多配置 50 条同步任务");
            Map<String,Object> a = account(); String zone = (String)a.get("zone_id");
            JsonNode record = remote.record(zone, recordId, token(a)); checkRecord(record, "");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE zone_id=? AND record_id=? AND id<>?", Integer.class, zone, recordId, id == null ? -1 : id) > 0)
                throw new IllegalArgumentException("该记录已被其他任务绑定");
            if (id == null) jdbc.update("INSERT INTO ddns_task(name,source,zone_id,record_id,record_name,enabled,target_ip) VALUES(?,?,?,?,?,?,?)",
                    name.trim(), source, zone, recordId, record.path("name").asText(), enabled ? 1 : 0, record.path("content").asText());
            else jdbc.update("UPDATE ddns_task SET name=?,source=?,zone_id=?,record_id=?,record_name=?,enabled=?,source_ip=NULL,target_ip=?,checked_at=NULL,updated_at=NULL,error='' WHERE id=?",
                    name.trim(), source, zone, recordId, record.path("name").asText(), enabled ? 1 : 0, record.path("content").asText(), id);
        } finally { lock.unlock(); }
    }
    public void delete(long id) { enter(); try { task(id); jdbc.update("DELETE FROM ddns_task WHERE id=?", id); } finally { lock.unlock(); } }
    public void enable(long id, boolean enabled) { enter(); try { task(id); jdbc.update("UPDATE ddns_task SET enabled=? WHERE id=?", enabled ? 1 : 0, id); } finally { lock.unlock(); } }

    public void sync(long id) {
        Map<String,Object> t = task(id);
        if (((Number)t.get("enabled")).intValue() != 1) throw new IllegalArgumentException("请先启用任务");
        if (!pending.add(id)) throw new IllegalStateException("该任务已在同步中");
        worker.execute(() -> { try { syncNow(id); } finally { pending.remove(id); } });
    }
    // Called by one dedicated worker: network I/O never blocks the shared scheduler or a DB transaction.
    void syncNow(long id) {
        lock.lock();
        String sourceIp = null, targetIp = null;
        try {
            Map<String,Object> t = task(id);
            if (((Number)t.get("enabled")).intValue() != 1) return;
            Map<String,Object> a = account(); String zone = (String)t.get("zone_id");
            if (!zone.equals(a.get("zone_id"))) throw new IllegalStateException("任务与凭证 Zone 不一致");
            String credential = token(a);
            sourceIp = remote.resolve((String)t.get("source"));
            JsonNode record = remote.record(zone, (String)t.get("record_id"), credential);
            checkRecord(record, (String)t.get("record_name"));
            targetIp = record.path("content").asText();
            if (!sourceIp.equals(targetIp)) {
                remote.update(zone, (String)t.get("record_id"), credential, sourceIp);
                targetIp = sourceIp;
                jdbc.update("UPDATE ddns_task SET updated_at=? WHERE id=?", System.currentTimeMillis(), id);
            }
            jdbc.update("UPDATE ddns_task SET source_ip=?,target_ip=?,checked_at=?,error='' WHERE id=?", sourceIp, targetIp, System.currentTimeMillis(), id);
        } catch (Exception ex) {
            // Remote exceptions are deliberately sanitized; do not persist request headers or response bodies.
            String message = ex instanceof IllegalArgumentException || ex instanceof IllegalStateException ? ex.getMessage() : "同步失败，请稍后重试";
            jdbc.update("UPDATE ddns_task SET source_ip=COALESCE(?,source_ip),target_ip=COALESCE(?,target_ip),checked_at=?,error=? WHERE id=?",
                    sourceIp, targetIp, System.currentTimeMillis(), message, id);
        } finally { lock.unlock(); }
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public synchronized void schedule() {
        if (scheduled) return;
        scheduled = true;
        worker.execute(() -> {
            try {
                for (Long id : jdbc.queryForList("SELECT id FROM ddns_task WHERE enabled=1 ORDER BY COALESCE(checked_at,0),id", Long.class)) {
                    if (!pending.add(id)) continue;
                    try { syncNow(id); } finally { pending.remove(id); }
                }
            } finally { scheduled = false; }
        });
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
