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

class ForwardTlsInboundTest {
    @Test void checksOwnershipPortsAndExistingDomains() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE forward_tls_inbound(id INTEGER PRIMARY KEY, tunnel_id INTEGER,name TEXT,port INTEGER,allowed_sni TEXT)");
        jdbc.execute("CREATE TABLE chain_tunnel(node_id INTEGER,tunnel_id INTEGER,chain_type INTEGER,port INTEGER)");
        jdbc.execute("CREATE TABLE forward_port(node_id INTEGER,port INTEGER)");
        jdbc.execute("CREATE TABLE forward(tls_inbound_id INTEGER,sni_names TEXT)");
        jdbc.update("INSERT INTO chain_tunnel VALUES(1,2,1,NULL)");
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "header." + payload + ".signature");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var controller = new ForwardTlsInboundController(jdbc);
            assertEquals(0, controller.create(Map.of("tunnelId",2,"name","entry","port",443,"allowedSni","example.com")).getCode());
            assertNotEquals(0, controller.create(Map.of("tunnelId",2,"name","duplicate","port",443)).getCode());
            jdbc.update("INSERT INTO forward VALUES(1,'app.example.com')");
            assertNotEquals(0, controller.update(Map.of("id",1,"name","narrow","allowedSni","other.com")).getCode());
            assertEquals(0, controller.update(Map.of("id",1,"name","renamed","allowedSni",".example.com")).getCode());
            assertNotEquals(0, controller.delete(Map.of("id",1)).getCode());
            jdbc.update("DELETE FROM forward");
            assertEquals(0, controller.delete(Map.of("id",1)).getCode());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            ds.destroy();
        }
    }
}
