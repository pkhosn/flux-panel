package com.admin;

import com.admin.service.impl.ForwardServiceImpl;
import com.admin.mapper.ForwardMapper;
import com.admin.controller.ForwardTrafficController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardAccountScopeTest {
    private void login(int role) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":1,\"name\":\"admin\",\"role_id\":"+role+"}").getBytes())+".s");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @Test void listDefaultsToSelfAndOnlyAdminCanInspectOthers() {
        var mapper = mock(ForwardMapper.class);
        when(mapper.selectForwardsWithTunnelByUserId(anyInt())).thenReturn(List.of());
        when(mapper.selectAllForwardsWithTunnel()).thenReturn(List.of());
        var service = new ForwardServiceImpl();
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        try {
            login(0);
            assertEquals(0,service.getAllForwards().getCode());
            verify(mapper).selectForwardsWithTunnelByUserId(1);
            assertEquals(0,service.getForwardsForUser(2).getCode());
            verify(mapper).selectForwardsWithTunnelByUserId(2);
            assertEquals(0,service.getForwardsForUser(0).getCode());
            verify(mapper).selectAllForwardsWithTunnel();
            clearInvocations(mapper);
            login(1);
            assertNotEquals(0,service.getForwardsForUser(2).getCode());
            assertNotEquals(0,service.getForwardsForUser(-1).getCode());
            assertNotEquals(0,service.getForwardsForUser(0).getCode());
            verifyNoInteractions(mapper);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void totalStatsArePersonalUnlessAdminExplicitlySelectsUser() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE forward_flow_hour(user_id INTEGER,hour_start INTEGER,bytes INTEGER)");
            long hour = System.currentTimeMillis()/3600000*3600000;
            jdbc.update("INSERT INTO forward_flow_hour VALUES(1,?,10),(2,?,20)",hour,hour);
            var controller = new ForwardTrafficController(jdbc);
            login(0);
            var own = (List<Map<String,Object>>)controller.stats(Map.of()).getData();
            assertEquals(10,((Number)own.get(0).get("bytes")).intValue());
            var other = (List<Map<String,Object>>)controller.stats(Map.of("userId",2)).getData();
            assertEquals(20,((Number)other.get(0).get("bytes")).intValue());
            var all = (List<Map<String,Object>>)controller.stats(Map.of("userId",0)).getData();
            assertEquals(30,((Number)all.get(0).get("bytes")).intValue());
            login(1);
            assertNotEquals(0,controller.stats(Map.of("userId",0)).getCode());
            assertNotEquals(0,controller.stats(Map.of("userId",2)).getCode());
        } finally { RequestContextHolder.resetRequestAttributes(); ds.destroy(); }
    }
}
