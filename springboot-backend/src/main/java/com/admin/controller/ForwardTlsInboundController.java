package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.ForwardSni;
import com.admin.common.utils.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward/tls-inbound")
public class ForwardTlsInboundController {
    private final JdbcTemplate jdbc;
    public ForwardTlsInboundController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/list")
    public R list() {
        if (JwtUtil.getRoleIdFromToken() != 0) {
            return R.ok(jdbc.queryForList("SELECT i.id,i.tunnel_id AS tunnelId,i.name,i.port,i.allowed_sni AS allowedSni " +
                    "FROM forward_tls_inbound i JOIN user_tunnel u ON u.tunnel_id=i.tunnel_id WHERE u.user_id=? AND u.status=1 ORDER BY i.id",
                    JwtUtil.getUserIdFromToken()));
        }
        return R.ok(jdbc.queryForList("SELECT id,tunnel_id AS tunnelId,name,port,allowed_sni AS allowedSni FROM forward_tls_inbound ORDER BY id"));
    }

    @PostMapping("/create")
    public synchronized R create(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            int tunnelId = Integer.parseInt(String.valueOf(input.get("tunnelId")));
            int port = Integer.parseInt(String.valueOf(input.get("port")));
            String name = String.valueOf(input.getOrDefault("name", "")).trim();
            String allowed = ForwardSni.normalize(String.valueOf(input.getOrDefault("allowedSni", "")), true);
            if (name.isEmpty() || name.length() > 64 || port < 1 || port > 65535) return R.err("入口名称或端口无效");
            var nodes = jdbc.queryForList("SELECT node_id FROM chain_tunnel WHERE tunnel_id=? AND chain_type=1", tunnelId);
            if (nodes.isEmpty()) return R.err("隧道没有入口节点");
            for (var node : nodes) {
                Object id = node.get("node_id");
                Integer used = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM forward_port WHERE node_id=? AND port=?) + " +
                        "(SELECT COUNT(*) FROM chain_tunnel WHERE node_id=? AND port=?) + " +
                        "(SELECT COUNT(*) FROM forward_tls_inbound i JOIN chain_tunnel c ON c.tunnel_id=i.tunnel_id " +
                        "WHERE c.chain_type=1 AND c.node_id=? AND i.port=?)", Integer.class, id, port, id, port, id, port);
                if (used != null && used > 0) return R.err("端口已被规则、隧道或共享入口占用");
            }
            jdbc.update("INSERT INTO forward_tls_inbound(tunnel_id,name,port,allowed_sni) VALUES (?,?,?,?)", tunnelId, name, port, allowed);
            return R.ok();
        } catch (IllegalArgumentException ex) { return R.err("入口参数或 SNI 域名无效"); }
    }

    @PostMapping("/delete")
    public synchronized R delete(@RequestBody Map<String, Object> input) {
        if (JwtUtil.getRoleIdFromToken() != 0) return R.err("仅管理员可管理共享 TLS 入口");
        try {
            long id = Long.parseLong(String.valueOf(input.get("id")));
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM forward WHERE tls_inbound_id=?", Integer.class, id);
            if (count != null && count > 0) return R.err("请先移除引用此入口的规则（包括暂停规则）");
            return jdbc.update("DELETE FROM forward_tls_inbound WHERE id=?", id) == 1 ? R.ok() : R.err("入口不存在");
        } catch (NumberFormatException ex) { return R.err("入口 ID 无效"); }
    }
}
