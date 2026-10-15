package com.admin;

import com.admin.entity.ChainTunnel;
import com.admin.entity.Forward;
import com.admin.service.impl.ForwardServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ForwardSharedAllocationTest {
    @Test void sharedRulesKeepReservedNamesAndRejectOtherPortUsers() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE forward_tls_inbound(id INTEGER,tunnel_id INTEGER,port INTEGER,allowed_sni TEXT)");
            jdbc.execute("CREATE TABLE forward(id INTEGER,tls_inbound_id INTEGER,sni_names TEXT,status INTEGER)");
            jdbc.execute("CREATE TABLE chain_tunnel(node_id INTEGER,port INTEGER)");
            jdbc.execute("CREATE TABLE forward_port(node_id INTEGER,port INTEGER,forward_id INTEGER)");
            jdbc.update("INSERT INTO forward_tls_inbound VALUES(5,9,443,'example.com')");
            jdbc.update("INSERT INTO forward VALUES(20,5,'reserved.example.com',0)");
            var service = new ForwardServiceImpl();
            ReflectionTestUtils.setField(service,"jdbc",jdbc);
            var rule = new Forward();
            rule.setTunnelId(9); rule.setTlsInboundId(5); rule.setProtocol("tcp");
            rule.setSniNames("app.example.com");
            var entry = new ChainTunnel(); entry.setNodeId(1L);
            List<ChainTunnel> entries = new ArrayList<>(List.of(entry));
            ReflectionTestUtils.invokeMethod(service,"allocateForwardPorts",rule,entries,null,0L);
            assertEquals(443,entry.getPort());
            rule.setSniNames("reserved.example.com");
            assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(service,"allocateForwardPorts",rule,entries,null,0L));
            rule.setSniNames("other.invalid");
            assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(service,"allocateForwardPorts",rule,entries,null,0L));
            rule.setSniNames("app.example.com");
            jdbc.update("INSERT INTO forward VALUES(21,0,'',1)");
            jdbc.update("INSERT INTO forward_port VALUES(1,443,21)");
            assertThrows(IllegalArgumentException.class,()->ReflectionTestUtils.invokeMethod(service,"allocateForwardPorts",rule,entries,null,0L));
        } finally { ds.destroy(); }
    }
}
