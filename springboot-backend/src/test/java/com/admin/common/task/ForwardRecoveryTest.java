package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.dto.GostDto;
import com.admin.common.utils.WebSocketServer;
import com.admin.service.impl.ForwardServiceImpl;
import com.alibaba.fastjson.JSONArray;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ForwardRecoveryTest {
    @Test void databaseFailureAndLostResponseSurviveRestart() throws Throwable {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE forward_recovery(node_id INTEGER PRIMARY KEY,services TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE node(id INTEGER,version TEXT)");
            jdbc.execute("CREATE TABLE forward(id INTEGER,tunnel_id INTEGER,status INTEGER)");
            jdbc.execute("CREATE TABLE forward_port(forward_id INTEGER,node_id INTEGER)");
            jdbc.execute("CREATE TABLE chain_tunnel(tunnel_id INTEGER,node_id INTEGER,chain_type INTEGER)");
            jdbc.update("INSERT INTO node VALUES(1,'2.0.16-beta'),(2,'2.0.16-beta')");
            jdbc.update("INSERT INTO forward VALUES(1,1,1)");
            jdbc.update("INSERT INTO forward_port VALUES(1,1),(1,2)");
            var manager = new DataSourceTransactionManager(ds);
            var recovery = spy(new ForwardRecovery(jdbc,manager));
            var forwards = mock(ForwardServiceImpl.class);
            when(forwards.recoveryServices(anySet())).thenReturn(Map.of(1L,new JSONArray(),2L,new JSONArray()));
            ReflectionTestUtils.setField(recovery,"forwards",forwards);
            var call = mock(ProceedingJoinPoint.class);
            var signature = mock(Signature.class);
            when(signature.getDeclaringTypeName()).thenReturn(ForwardServiceImpl.class.getName());
            when(call.getSignature()).thenReturn(signature);
            var tunnelEdit = new com.admin.common.dto.TunnelUpdateDto();
            tunnelEdit.setId(1L);
            var entry = new com.admin.entity.ChainTunnel(); entry.setNodeId(2L);
            tunnelEdit.setInNodeId(java.util.List.of(entry));
            when(call.getArgs()).thenReturn(new Object[]{tunnelEdit});
            when(call.proceed()).thenAnswer(invocation -> {
                jdbc.update("UPDATE forward SET status=0 WHERE id=1");
                throw new IllegalStateException("injected DB failure after first write");
            });
            {
                GostDto ok = new GostDto(); ok.setMsg("OK");
                GostDto lost = new GostDto(); lost.setMsg("response lost");
                doReturn(ok).when(recovery).sendRecovery(eq(1L),any());
                doReturn(lost).when(recovery).sendRecovery(eq(2L),any());
                assertNotEquals(0,((R)recovery.around(call)).getCode());
                assertEquals(1,jdbc.queryForObject("SELECT status FROM forward WHERE id=1",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM forward_recovery",Integer.class));
                assertNotEquals(0,((R)recovery.around(call)).getCode());
                verify(call,times(1)).proceed();
                // A new component instance reads the same durable task after restart.
                var restarted = spy(new ForwardRecovery(jdbc,manager));
                doReturn(ok).when(restarted).sendRecovery(eq(2L),any());
                restarted.restore();
                assertFalse(restarted.pending());
                doAnswer(invocation -> {jdbc.update("UPDATE forward SET status=0 WHERE id=1");return R.ok();}).when(call).proceed();
                assertEquals(0,((R)recovery.around(call)).getCode());
                assertFalse(recovery.pending());
                assertEquals(0,jdbc.queryForObject("SELECT status FROM forward WHERE id=1",Integer.class));
            }
        } finally {ds.destroy();}
    }

    @Test void oldAgentsAreRejectedBeforeMutation() {
        assertFalse(ForwardRecovery.supportsRecovery("2.0.15-beta"));
        assertFalse(ForwardRecovery.supportsRecovery(null));
        assertTrue(ForwardRecovery.supportsRecovery("2.0.16-beta"));
        assertTrue(ForwardRecovery.supportsRecovery("2.1.0-beta"));
    }
}
