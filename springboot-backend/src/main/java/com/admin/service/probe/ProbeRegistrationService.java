package com.admin.service.probe;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Internal registration primitives; HTTP exposure follows Agent mode enforcement. */
@Service
public class ProbeRegistrationService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ProbeRegistrationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public String issue(int userId) {
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM probe_registration WHERE expires_at<=?", now);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM probe_registration WHERE user_id=?", Integer.class, userId);
        if (pending != null && pending >= 10) throw new IllegalArgumentException("有效安装命令过多，请15分钟后重试");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO probe_registration(token_hash,user_id,expires_at) VALUES(?,?,?)",
                hash(token), userId, now + 15 * 60_000L);
        return token;
    }

    @Transactional
    public int consume(String token) {
        if (token == null || token.length() != 43) throw new IllegalArgumentException("安装凭证无效或已过期");
        String digest = hash(token);
        // Atomic write precedes read: one transaction owns the token during consumption.
        jdbc.update("UPDATE probe_registration SET expires_at=expires_at WHERE token_hash=?", digest);
        List<Integer> owners = jdbc.queryForList("SELECT user_id FROM probe_registration WHERE token_hash=? AND expires_at>?",
                Integer.class, digest, System.currentTimeMillis());
        if (owners.size() != 1) throw new IllegalArgumentException("安装凭证无效或已过期");
        jdbc.update("DELETE FROM probe_registration WHERE token_hash=?", digest);
        return owners.getFirst();
    }

    @Transactional
    public Map<String,Object> register(String token, String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("探针名称长度应为1–100个字符");
        }
        int owner = consume(token);
        byte[] bytes = new byte[24]; random.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO node(name,secret,server_ip,port,status,created_time,updated_time,probe_mode,owner_user_id,created_by_role) " +
                "VALUES(?,?,'','',0,?,?,1,?,1)", name.trim(), secret, now, now, owner);
        Long id = jdbc.queryForObject("SELECT id FROM node WHERE secret=?", Long.class,secret);
        return Map.of("id",id,"secret",secret,"probe_mode",true);
    }

    public List<Map<String,Object>> listOwned(int userId) {
        return jdbc.queryForList("SELECT id,name,status,server_ip,detected_ipv4,detected_ipv6,version FROM node WHERE probe_mode=1 AND owner_user_id=? ORDER BY id DESC",userId);
    }

    public void requireOwner(long nodeId, int userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE id=? AND probe_mode=1 AND owner_user_id=?",
                Integer.class,nodeId,userId);
        if (!Integer.valueOf(1).equals(count)) throw new IllegalArgumentException("探针不存在或无权限");
    }

    @Transactional
    public void delete(long nodeId, int userId) {
        // Conditional write avoids both an ownership bypass and a stale snapshot on concurrent activation.
        int changed = jdbc.update("DELETE FROM node WHERE id=? AND probe_mode=1 AND owner_user_id=?", nodeId, userId);
        if (changed != 1) throw new IllegalArgumentException("探针不存在或已启用转发，请刷新后重试");
    }

    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
