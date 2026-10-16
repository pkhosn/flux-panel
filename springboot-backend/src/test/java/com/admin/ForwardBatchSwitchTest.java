package com.admin;

import com.admin.controller.ForwardBatchController;
import com.admin.service.ForwardService;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardBatchSwitchTest {
    @Test void fixedPortConflictNeverFallsBackToAnotherPort() {
        var service = spy(new com.admin.service.impl.ForwardServiceImpl());
        doReturn(List.of(9002, 9003)).when(service).getNodePort(1L, 20L);
        var entry = new com.admin.entity.ChainTunnel(); entry.setNodeId(1L);
        assertThrows(IllegalArgumentException.class, () -> service.get_port(List.of(entry), 9001, 20L));
        assertNull(entry.getPort());
        service.get_port(List.of(entry), 9002, 20L);
        assertEquals(9002, entry.getPort());
    }

    @Test void preservesPortsAndReportsEachFailureWhileContinuing() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE forward(id INTEGER,user_id INTEGER,name TEXT,tunnel_id INTEGER,remote_addr TEXT,strategy TEXT,protocol TEXT,proxy_accept INTEGER DEFAULT 0,proxy_send INTEGER DEFAULT 0,proxy_trusted_cidrs TEXT,origin_tls INTEGER DEFAULT 0,origin_server_name TEXT,origin_root_ca TEXT,origin_alpn TEXT,group_id INTEGER,status INTEGER DEFAULT 1,tls_inbound_id INTEGER DEFAULT 0)");
            jdbc.execute("CREATE TABLE forward_port(forward_id INTEGER,port INTEGER)");
            jdbc.execute("CREATE TABLE tunnel(id INTEGER,status INTEGER)");
            jdbc.execute("CREATE TABLE user_tunnel(tunnel_id INTEGER,user_id INTEGER,status INTEGER)");
            jdbc.update("INSERT INTO tunnel VALUES(9,1)");
            jdbc.update("INSERT INTO user_tunnel VALUES(9,1,1)");
            for (int id = 1; id <= 3; id++) {
                jdbc.update("INSERT INTO forward(id,user_id,name,tunnel_id,remote_addr,protocol) VALUES(?,1,?,2,'example.com:80','tcp')",id,"rule-"+id);
                jdbc.update("INSERT INTO forward_port VALUES(?,?)",id,9000+id);
            }
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", "h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes())+".s");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var service = mock(ForwardService.class);
            List<ForwardUpdateDto> attempted = new ArrayList<>();
            when(service.updateForward(any())).thenAnswer(inv -> {
                ForwardUpdateDto dto = inv.getArgument(0); attempted.add(dto);
                assertEquals(9000 + dto.getId().intValue(),dto.getInPort());
                assertEquals(9,dto.getTunnelId());
                if (dto.getId()==1) return R.err("端口冲突");
                if (dto.getId()==2) throw new IllegalArgumentException("端口不可用");
                return R.ok();
            });
            var controller = new ForwardBatchController(jdbc,service);
            var result = controller.batch(Map.of("ids",List.of(1,2,3),"action","switch","tunnelId",9));
            assertEquals(0,result.getCode());
            var data = (Map<?,?>)result.getData();
            assertEquals(1,data.get("count"));
            assertEquals(List.of(3L),data.get("succeededIds"));
            var failures = (List<Map<String,Object>>)data.get("failures");
            assertEquals(2,failures.size());
            assertEquals("rule-1",failures.get(0).get("name"));
            assertEquals(9001,failures.get(0).get("port"));
            assertEquals("端口冲突",failures.get(0).get("reason"));
            assertEquals(3,attempted.size());
            verify(service,times(3)).updateForward(any());
            clearInvocations(service);
            jdbc.update("UPDATE forward SET user_id=2 WHERE id=1");
            request.removeHeader("Authorization");
            request.addHeader("Authorization", "h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":1}".getBytes())+".s");
            assertNotEquals(0,controller.batch(Map.of("ids",List.of(1,3),"action","switch","tunnelId",9)).getCode());
            verifyNoInteractions(service);
        } finally {RequestContextHolder.resetRequestAttributes();ds.destroy();}
    }
}
