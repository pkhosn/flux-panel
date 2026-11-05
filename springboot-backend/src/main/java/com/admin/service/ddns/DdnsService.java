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
    private Map<String,Object> account(long owner)  {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT zone_id,token_cipher FROM ddns_account WHERE owner_user_id=?", owner);
        if (rows.isEmpty()) throw new IllegalStateException("请先保存 Cloudflare 凭证");
        return rows.get(0);
    }
    private String token(Map<String,Object> account) { return crypto.decrypt((String)account.get("token_cipher")); }
    public Map<String,Object> list(long owner) {
        List<Map<String,Object>> accounts = jdbc.queryForList("SELECT zone_id FROM ddns_account WHERE owner_user_id=?", owner);
        List<Map<String,Object>> tasks = jdbc.queryForList("SELECT * FROM ddns_task WHERE owner_user_id=? ORDER BY id", owner);
        for (Map<String,Object> task : tasks) task.put("running", pending.contains(((Number)task.get("id")).longValue()));
        return Map.of("configured", !accounts.isEmpty(), "zoneId", accounts.isEmpty() ? "" : accounts.get(0).get("zone_id"), "tasks", tasks);
    }
    private void enter() {
        if (!lock.tryLock()) throw new IllegalStateException("正在同步或保存配置，请稍后重试");
    }
    public void saveAccount(long owner, String zone, String rawToken) {
        zone = DdnsRemote.id(zone);
        enter();
        try {
            String value = rawToken == null ? "" : rawToken.trim();
            if (value.isEmpty()) value = token(account(owner));
            if (!value.matches("[A-Za-z0-9_-]{20,256}")) throw new IllegalArgumentException("API Token 格式无效");
            remote.zone(zone, value);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE owner_user_id=? AND zone_id<>?", Integer.class, owner, zone) > 0)
                throw new IllegalArgumentException("请先删除旧 Zone 的同步任务再切换 Zone");
            jdbc.update("INSERT INTO ddns_account(owner_user_id,zone_id,token_cipher) VALUES(?,?,?) ON CONFLICT(owner_user_id) DO UPDATE SET zone_id=excluded.zone_id,token_cipher=excluded.token_cipher", owner, zone, crypto.encrypt(value));
        } finally { lock.unlock(); }
    }
    public List<Map<String,Object>> records(long owner) {
        Map<String,Object> a = account(owner); return remote.records((String)a.get("zone_id"), token(a));
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
    private Map<String,Object> task(long owner, long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM ddns_task WHERE id=? AND owner_user_id=?", id, owner);
        if (rows.isEmpty()) throw new IllegalArgumentException("同步任务不存在");
        return rows.get(0);
    }
    private void checkRecord(JsonNode record, String expectedName) {
        if (!"A".equals(record.path("type").asText()) || record.path("proxied").asBoolean()
                || (!expectedName.isEmpty() && !expectedName.equals(record.path("name").asText())))
            throw new IllegalArgumentException("目标记录名称或类型已改变，或已启用代理；请重新绑定仅 DNS 的 A 记录");
    }
    public void saveTask(long owner, Long id, String name, String source, String recordId, boolean enabled, int interval) {
        if (name == null || name.isBlank() || name.trim().length() > 64) throw new IllegalArgumentException("名称须为 1–64 个字符");
        if (!Set.of(1,3,5,10,15,30,60).contains(interval)) throw new IllegalArgumentException("同步间隔无效");
        source = hostname(source); recordId = DdnsRemote.id(recordId);
        enter();
        try {
            if (id != null && !"single".equals(task(owner,id).getOrDefault("mode","single"))) throw new IllegalArgumentException("不能切换已保存任务的模式");
            else if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE owner_user_id=?", Integer.class, owner) >= 20) throw new IllegalArgumentException("每人最多配置 20 条同步任务");
            Map<String,Object> a = account(owner); String zone = (String)a.get("zone_id");
            JsonNode record = remote.record(zone, recordId, token(a)); checkRecord(record, "");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE zone_id=? AND record_name=? AND mode='pool'",Integer.class,zone,record.path("name").asText())>0)
                throw new IllegalArgumentException("目标域名已被多IP任务管理");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE zone_id=? AND record_id=? AND id<>?", Integer.class, zone, recordId, id == null ? -1 : id) > 0)
                throw new IllegalArgumentException("该记录已被其他任务绑定");
            if (id == null) jdbc.update("INSERT INTO ddns_task(name,source,zone_id,record_id,record_name,enabled,target_ip,owner_user_id,interval_minutes) VALUES(?,?,?,?,?,?,?,?,?)",
                    name.trim(), source, zone, recordId, record.path("name").asText(), enabled ? 1 : 0, record.path("content").asText(), owner, interval);
            else jdbc.update("UPDATE ddns_task SET name=?,source=?,zone_id=?,record_id=?,record_name=?,enabled=?,source_ip=NULL,target_ip=?,checked_at=NULL,updated_at=NULL,error='',interval_minutes=? WHERE id=? AND owner_user_id=?",
                    name.trim(), source, zone, recordId, record.path("name").asText(), enabled ? 1 : 0, record.path("content").asText(), interval, id, owner);
        } finally { lock.unlock(); }
    }
    public void savePool(long owner,Long id,String name,String sources,String target,boolean enabled,int interval) {
        if(name==null || name.isBlank() || name.trim().length()>64) throw new IllegalArgumentException("名称须为1–64个字符");
        if(!Set.of(1,3,5,10,15,30,60).contains(interval)) throw new IllegalArgumentException("同步间隔无效");
        target=hostname(target);
        String[] raw=sources.trim().split("[\\s,，]+");
        Set<String> hosts=new LinkedHashSet<>();for(String host:raw)hosts.add(hostname(host));
        if(hosts.isEmpty() || hosts.size()>10 || hosts.contains(target)) throw new IllegalArgumentException("填写1–10个来源域名，不能包含目标自身");
        enter();
        try {
            if(id!=null) task(owner,id);
            Map<String,Object> a=account(owner);String zone=(String)a.get("zone_id");String credential=token(a);
            String zoneName=remote.zone(zone,credential);
            if(!target.equals(zoneName) && !target.endsWith("."+zoneName)) throw new IllegalArgumentException("目标不属于当前Zone");
            if(id!=null) {
                var old=task(owner,id);
                if(!"pool".equals(old.get("mode")) || !target.equals(old.get("record_name"))) throw new IllegalArgumentException("任务模式和目标保存后不能更改，请新建任务");
            } else if(jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE owner_user_id=?",Integer.class,owner)>=20) throw new IllegalArgumentException("每人最多20条任务");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task WHERE zone_id=? AND record_name=? AND id<>?",Integer.class,zone,target,id==null?-1:id)>0)throw new IllegalArgumentException("目标域名已被其他任务绑定");
            for(JsonNode r:remote.namedRecords(zone,credential,target))
                if("CNAME".equals(r.path("type").asText()) || ("A".equals(r.path("type").asText()) && r.path("proxied").asBoolean())) throw new IllegalArgumentException("请先手动移除目标CNAME并关闭目标A记录代理");
            String source=String.join("\n",hosts);
            if(id==null)jdbc.update("INSERT INTO ddns_task(name,source,zone_id,record_id,record_name,enabled,owner_user_id,interval_minutes,mode) VALUES(?,?,?,?,?,?,?,?,'pool')",name.trim(),source,zone,java.util.UUID.randomUUID().toString().replace("-",""),target,enabled?1:0,owner,interval);
            else {
                jdbc.update("UPDATE ddns_task SET name=?,source=?,enabled=?,interval_minutes=?,checked_at=NULL,error='' WHERE id=?",name.trim(),source,enabled?1:0,interval,id);
                jdbc.update("UPDATE ddns_managed SET missing_count=0,last_missing=0 WHERE task_id=?",id);
            }
        } finally { lock.unlock(); }
    }

    private void syncPool(Map<String,Object> task,String credential) {
        long id=((Number)task.get("id")).longValue();String zone=(String)task.get("zone_id"),name=(String)task.get("record_name");
        Set<String> desired=new TreeSet<>();
        for(String source:((String)task.get("source")).split("\n"))desired.addAll(remote.resolveAll(source));
        if(desired.isEmpty() || desired.size()>50)throw new IllegalStateException("来源合并后需有1–50个公网IPv4，保留原记录");
        String marker="flux-ddns:"+task.get("record_id");
        List<JsonNode> current=remote.namedRecords(zone,credential,name);
        for(JsonNode r:current) {
            if(!name.equals(r.path("name").asText()))throw new IllegalStateException("目标查询返回不匹配记录");
            if("CNAME".equals(r.path("type").asText()) || ("A".equals(r.path("type").asText())&&r.path("proxied").asBoolean()))throw new IllegalStateException("目标存在CNAME或代理A记录，请先手动处理");
        }
        Map<String,JsonNode> byId=new HashMap<>();Set<String> existing=new HashSet<>();
        for(JsonNode r:current) {
            byId.put(r.path("id").asText(),r);
            if("A".equals(r.path("type").asText()))existing.add(r.path("content").asText());
            if(marker.equals(r.path("comment").asText()) && "A".equals(r.path("type").asText()))
                jdbc.update("INSERT OR IGNORE INTO ddns_managed(task_id,record_id,ip) VALUES(?,?,?)",id,r.path("id").asText(),r.path("content").asText());
        }
        boolean changed=false;
        for(String ip:desired)if(!existing.contains(ip)) {
            JsonNode created=remote.create(zone,credential,name,ip,marker);
            String record=DdnsRemote.id(created.path("id").asText());
            jdbc.update("INSERT OR IGNORE INTO ddns_managed(task_id,record_id,ip) VALUES(?,?,?)",id,record,ip);
            changed=true;existing.add(ip);
        }
        long now=System.currentTimeMillis(),spacing=((Number)task.get("interval_minutes")).longValue()*60000;
        for(Map<String,Object> managed:jdbc.queryForList("SELECT * FROM ddns_managed WHERE task_id=?",id)) {
            String record=(String)managed.get("record_id"),ip=(String)managed.get("ip");JsonNode r=byId.get(record);
            if(desired.contains(ip)){jdbc.update("UPDATE ddns_managed SET missing_count=0,last_missing=0 WHERE task_id=? AND record_id=?",id,record);continue;}
            if(r==null){jdbc.update("DELETE FROM ddns_managed WHERE task_id=? AND record_id=?",id,record);continue;}
            if(!marker.equals(r.path("comment").asText()) || !ip.equals(r.path("content").asText()) || !"A".equals(r.path("type").asText()))throw new IllegalStateException("工具管理的记录已被手动修改，请恢复记录或删除任务重新配置");
            int missing=((Number)managed.get("missing_count")).intValue();long last=((Number)managed.get("last_missing")).longValue();
            if(now-last<spacing)continue;
            missing++;
            if(missing>=3) {
                // Re-read immediately before deleting; never delete an altered or unmarked record.
                JsonNode fresh=remote.record(zone,record,credential);checkRecord(fresh,name);
                if(!marker.equals(fresh.path("comment").asText()) || !ip.equals(fresh.path("content").asText()))throw new IllegalStateException("记录已被修改，停止删除");
                remote.remove(zone,credential,record);
                existing.remove(ip);
                jdbc.update("DELETE FROM ddns_managed WHERE task_id=? AND record_id=?",id,record);changed=true;
            } else jdbc.update("UPDATE ddns_managed SET missing_count=?,last_missing=? WHERE task_id=? AND record_id=?",missing,now,id,record);
        }
        if(changed)jdbc.update("UPDATE ddns_task SET updated_at=? WHERE id=?",now,id);
        jdbc.update("UPDATE ddns_task SET source_ip=?,target_ip=?,checked_at=?,error='' WHERE id=?",String.join(", ",desired),String.join(", ",new TreeSet<>(existing)),now,id);
    }

    public void delete(long owner, long id) { enter(); try { task(owner, id); jdbc.update("DELETE FROM ddns_managed WHERE task_id=?",id); jdbc.update("DELETE FROM ddns_task WHERE id=?", id); } finally { lock.unlock(); } }
    public void enable(long owner, long id, boolean enabled) { enter(); try { task(owner, id); jdbc.update("UPDATE ddns_task SET enabled=? WHERE id=?", enabled ? 1 : 0, id); } finally { lock.unlock(); } }

    public void sync(long owner, long id) {
        Map<String,Object> t = task(owner, id);
        if (((Number)t.get("enabled")).intValue() != 1) throw new IllegalArgumentException("请先启用任务");
        if (!pending.add(id)) throw new IllegalStateException("该任务已在同步中");
        worker.execute(() -> { try { syncNow(owner, id); } finally { pending.remove(id); } });
    }
    // Called by one dedicated worker: network I/O never blocks the shared scheduler or a DB transaction.
    void syncNow(long owner, long id) { syncNow(owner, id, true); }
    private void syncNow(long owner, long id, boolean manual) {
        lock.lock();
        String sourceIp = null, targetIp = null;
        boolean pool = false;
        try {
            Map<String,Object> t = task(owner, id);
            if (((Number)t.get("enabled")).intValue() != 1) return;
            Number checked = (Number)t.get("checked_at");
            if (!manual && checked != null && checked.longValue() > System.currentTimeMillis()
                    - ((Number)t.get("interval_minutes")).longValue()*60000) return;
            Map<String,Object> a = account(owner); String zone = (String)t.get("zone_id");
            if (!zone.equals(a.get("zone_id"))) throw new IllegalStateException("任务与凭证 Zone 不一致");
            String credential = token(a);
            if ("pool".equals(t.get("mode"))) {
                pool = true;
                syncPool(t,credential);
                return;
            }
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
            if(pool) jdbc.update("UPDATE ddns_managed SET missing_count=0,last_missing=0 WHERE task_id=?",id);
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
                for (Map<String,Object> row : jdbc.queryForList("SELECT id,owner_user_id FROM ddns_task WHERE enabled=1 AND (checked_at IS NULL OR checked_at<=?-interval_minutes*60000) ORDER BY COALESCE(checked_at,0),id", System.currentTimeMillis())) {
                    long id = ((Number)row.get("id")).longValue();
                    long owner = ((Number)row.get("owner_user_id")).longValue();
                    if (!pending.add(id)) continue;
                    try { syncNow(owner, id, false); } finally { pending.remove(id); }
                }
            } finally { scheduled = false; }
        });
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
