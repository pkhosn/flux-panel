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
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='forward_group'", Integer.class));
    }




}
