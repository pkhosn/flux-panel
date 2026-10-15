package com.admin;

import com.admin.common.dto.GostDto;
import com.admin.common.utils.GostUtil;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.entity.UserTunnel;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.admin.service.UserService;
import com.admin.service.UserTunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;


@SpringBootTest
class AdminApplicationTests {

    @Resource
    private JdbcTemplate jdbc;


    @Test
    public void test(){
        assertArrayEquals(new String[]{"tcp", "udp"}, GostUtil.forwardProtocols(null));
        assertArrayEquals(new String[]{"tcp", "udp"}, GostUtil.forwardProtocols("tcp_udp"));
        assertArrayEquals(new String[]{"tcp"}, GostUtil.forwardProtocols("tcp"));
        assertArrayEquals(new String[]{"udp"}, GostUtil.forwardProtocols("udp"));
        assertNull(GostUtil.normalizeForwardProtocol("invalid"));
        assertEquals(2, GostUtil.forwardServiceNames("rule", "tcp_udp").size());
        assertEquals("rule_udp", GostUtil.forwardServiceNames("rule", "udp").getString(0));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('forward') WHERE name='protocol'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('forward') WHERE name='group_id'", Integer.class));
        for (String column : List.of("proxy_accept", "proxy_send", "proxy_trusted_cidrs", "origin_tls", "origin_server_name", "origin_root_ca", "origin_alpn", "tls_inbound_id", "sni_names")) {
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pragma_table_info('forward') WHERE name=?", Integer.class, column));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='forward_tls_inbound'", Integer.class));
        assertNull(GostUtil.validateForwardProxy("tcp", 1, 2, "127.0.0.1/8,::1/128"));
        assertNotNull(GostUtil.validateForwardProxy("udp", 1, 0, "127.0.0.1/8"));
        assertNotNull(GostUtil.validateForwardProxy("tcp", 1, 0, ""));
        assertNotNull(GostUtil.validateForwardProxy("tcp", 1, 0, "0.0.0.0/33"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='forward_group'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info('forward') WHERE name='traffic_baseline'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='forward_flow_hour'", Integer.class));
    }




}
