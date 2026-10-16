package com.admin;

import com.admin.config.SQLiteConfig;
import com.admin.entity.Tunnel;
import com.admin.mapper.TunnelMapper;
import com.admin.service.impl.TunnelServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeGroupingTest {
    @Test void migratesOldNodesAndReturnsOnlyIngressGroups() throws Exception {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE node(id INTEGER,name TEXT)");
            jdbc.update("INSERT INTO node VALUES(1,'old'),(2,'exit')");
            var config = new SQLiteConfig(ds);
            ReflectionTestUtils.invokeMethod(config,"ensureColumn",ds.getConnection(),"node","group_name","TEXT NOT NULL DEFAULT ''");
            ReflectionTestUtils.invokeMethod(config,"ensureColumn",ds.getConnection(),"node","group_name","TEXT NOT NULL DEFAULT ''");
            assertEquals("",jdbc.queryForObject("SELECT group_name FROM node WHERE id=1",String.class));
            jdbc.update("UPDATE node SET group_name='Japan' WHERE id=1");
            jdbc.update("UPDATE node SET group_name='HK' WHERE id=2");
            jdbc.execute("CREATE TABLE chain_tunnel(tunnel_id INTEGER,node_id INTEGER,chain_type INTEGER)");
            jdbc.update("INSERT INTO chain_tunnel VALUES(1,1,1),(1,2,3)");
            var mapper = mock(TunnelMapper.class);
            var tunnel = new Tunnel(); tunnel.setId(1L);
            when(mapper.selectList(any())).thenReturn(List.of(tunnel));
            var service = new TunnelServiceImpl();
            ReflectionTestUtils.setField(service,"baseMapper",mapper);
            ReflectionTestUtils.setField(service,"tlsJdbc",jdbc);
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes())+".s");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertEquals(0,service.userTunnel().getCode());
            assertEquals(List.of("Japan"),tunnel.getEntryGroups());
        } finally { RequestContextHolder.resetRequestAttributes(); ds.destroy(); }
    }
}
