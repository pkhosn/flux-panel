package com.admin;

import com.admin.controller.ForwardTlsInboundController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.admin.service.ForwardService;
import com.admin.common.lang.R;

class ForwardTlsInboundTest {
    @Test void checksOwnershipPortsAndExistingDomains() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE forward_tls_inbound(id INTEGER PRIMARY KEY, tunnel_id INTEGER,name TEXT,port INTEGER,allowed_sni TEXT,enabled INTEGER DEFAULT 1,suspended_ids TEXT DEFAULT '')");
        jdbc.execute("CREATE TABLE chain_tunnel(node_id INTEGER,tunnel_id INTEGER,chain_type INTEGER,port INTEGER)");
        jdbc.execute("CREATE TABLE forward_port(node_id INTEGER,port INTEGER)");
        jdbc.execute("CREATE TABLE forward(id INTEGER,tls_inbound_id INTEGER,sni_names TEXT,status INTEGER)");
        jdbc.update("INSERT INTO chain_tunnel VALUES(1,2,1,NULL)");
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var controller = new ForwardTlsInboundController(jdbc);
            assertEquals(0, controller.create(Map.of("tunnelId",2,"name","entry","port",443,"allowedSni","example.com")).getCode());
            assertNotEquals(0, controller.create(Map.of("tunnelId",2,"name","duplicate","port",443)).getCode());
            jdbc.update("INSERT INTO forward VALUES(10,1,'app.example.com',1)");
            assertNotEquals(0, controller.update(Map.of("id",1,"name","narrow","allowedSni","other.com")).getCode());
            assertEquals(0, controller.update(Map.of("id",1,"name","renamed","allowedSni",".example.com")).getCode());
            assertNotEquals(0, controller.delete(Map.of("id",1)).getCode());
            var forwards = mock(ForwardService.class);
            ReflectionTestUtils.setField(controller,"forwardService",forwards);
            when(forwards.pauseForward(10L)).thenAnswer(call -> { jdbc.update("UPDATE forward SET status=0 WHERE id=10"); return R.ok(); });
            when(forwards.resumeForward(10L)).thenAnswer(call -> { jdbc.update("UPDATE forward SET status=1 WHERE id=10"); return R.ok(); });
            jdbc.update("INSERT INTO forward VALUES(11,1,'manual.example.com',0)");
            assertEquals(0, controller.toggle(Map.of("id",1,"enabled",0)).getCode());
            assertEquals(0, jdbc.queryForObject("SELECT enabled FROM forward_tls_inbound WHERE id=1",Integer.class));
            assertEquals(0, controller.toggle(Map.of("id",1,"enabled",1)).getCode());
            assertEquals(1, jdbc.queryForObject("SELECT status FROM forward WHERE id=10",Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT status FROM forward WHERE id=11",Integer.class));
            verify(forwards).pauseForward(10L);
            verify(forwards).resumeForward(10L);
            verify(forwards,never()).resumeForward(11L);
            doReturn(R.err("node unavailable")).when(forwards).pauseForward(10L);
            assertNotEquals(0, controller.toggle(Map.of("id",1,"enabled",0)).getCode());
            assertEquals(1, jdbc.queryForObject("SELECT enabled FROM forward_tls_inbound WHERE id=1",Integer.class));
            jdbc.update("DELETE FROM forward");
            assertEquals(0, controller.delete(Map.of("id",1)).getCode());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            ds.destroy();
        }
    }
}
