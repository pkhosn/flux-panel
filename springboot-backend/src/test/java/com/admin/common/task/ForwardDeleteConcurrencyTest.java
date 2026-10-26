package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.service.impl.ForwardServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardDeleteConcurrencyTest {
    @TempDir Path temp;
    @Test void deletionAndConcurrentTrafficBothCommit() throws Throwable {
        var config = new HikariConfig(); config.setJdbcUrl("jdbc:sqlite:"+temp.resolve("delete.db"));
        config.setMaximumPoolSize(3); config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        var worker = Executors.newSingleThreadExecutor();
        try(var ds = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(ds); jdbc.execute("PRAGMA journal_mode=WAL");
            jdbc.execute("CREATE TABLE forward_recovery(node_id INTEGER PRIMARY KEY,services TEXT)");
            jdbc.execute("CREATE TABLE forward(id INTEGER,tunnel_id INTEGER)");
            jdbc.execute("CREATE TABLE forward_port(forward_id INTEGER,node_id INTEGER)");
            jdbc.execute("CREATE TABLE chain_tunnel(tunnel_id INTEGER,node_id INTEGER,chain_type INTEGER)");
            jdbc.execute("CREATE TABLE node(id INTEGER,version TEXT)");
            jdbc.execute("CREATE TABLE traffic(value INTEGER)");
            jdbc.update("INSERT INTO forward VALUES(1,26)"); jdbc.update("INSERT INTO forward_port VALUES(1,21)");
            jdbc.update("INSERT INTO node VALUES(21,'2.0.16-beta')"); jdbc.update("INSERT INTO traffic VALUES(0)");
            var recovery = new ForwardRecovery(jdbc,new DataSourceTransactionManager(ds));
            var forwards = mock(ForwardServiceImpl.class); when(forwards.recoveryServices(anySet())).thenReturn(Map.of());
            ReflectionTestUtils.setField(recovery,"forwards",forwards);
            var call = mock(ProceedingJoinPoint.class); var signature = mock(Signature.class);
            when(signature.getName()).thenReturn("deleteForward"); when(signature.getDeclaringTypeName()).thenReturn(ForwardServiceImpl.class.getName());
            when(call.getSignature()).thenReturn(signature); when(call.getArgs()).thenReturn(new Object[]{1L});
            List<Future<Integer>> writes = new ArrayList<>();
            when(call.proceed()).thenAnswer(inv -> {
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM forward",Integer.class));
                CountDownLatch started = new CountDownLatch(1);
                var write = worker.submit(() -> { started.countDown(); return jdbc.update("UPDATE traffic SET value=value+1"); });
                writes.add(write); assertTrue(started.await(3,TimeUnit.SECONDS));
                try { write.get(300,TimeUnit.MILLISECONDS); } catch(TimeoutException expected) { }
                jdbc.update("DELETE FROM forward_port WHERE forward_id=1");
                jdbc.update("DELETE FROM forward WHERE id=1"); return R.ok();
            });
            assertEquals(0,((R)recovery.around(call)).getCode());
            assertEquals(1,writes.getFirst().get(5,TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM forward",Integer.class));
            assertFalse(recovery.pending());
        } finally { worker.shutdownNow(); }
    }
}
