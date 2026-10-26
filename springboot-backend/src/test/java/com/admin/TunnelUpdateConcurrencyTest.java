package com.admin;

import com.admin.common.dto.TunnelUpdateDto;
import com.admin.entity.*;
import com.admin.service.*;
import com.admin.service.impl.TunnelServiceImpl;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TunnelUpdateConcurrencyTest {
    @TempDir Path temp;

    @Test void addingEntrySurvivesConcurrentHeartbeatWrite() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + temp.resolve("tunnel.db"));
        config.setMaximumPoolSize(3);
        config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var ds = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("PRAGMA journal_mode=WAL");
            jdbc.execute("CREATE TABLE tunnel(id INTEGER PRIMARY KEY,name TEXT)");
            jdbc.execute("CREATE TABLE node(id INTEGER PRIMARY KEY,status INTEGER)");
            jdbc.execute("CREATE TABLE forward_tls_inbound(tunnel_id INTEGER)");
            jdbc.update("INSERT INTO tunnel VALUES(1,'old')");
            jdbc.update("INSERT INTO node VALUES(2,1)");
            var service = spy(new TunnelServiceImpl() {
                @Override protected com.admin.common.dto.GostDto syncEntryServices(Long node, com.alibaba.fastjson.JSONArray desired) {
                    var result = new com.admin.common.dto.GostDto(); result.setMsg("OK"); return result;
                }
            });
            ReflectionTestUtils.setField(service,"tlsJdbc",jdbc);
            var chains = mock(ChainTunnelService.class);
            when(chains.list(any())).thenReturn(List.of());
            when(chains.saveBatch(anyCollection())).thenReturn(true);
            ReflectionTestUtils.setField(service,"chainTunnelService",chains);
            var forwards = mock(ForwardService.class);
            when(forwards.list(any())).thenReturn(List.of());
            when(forwards.recoveryServices(anySet())).thenReturn(Map.of());
            ReflectionTestUtils.setField(service,"forwardService",forwards);
            doAnswer(inv -> {
                jdbc.queryForObject("SELECT name FROM tunnel WHERE id=1",String.class);
                Tunnel tunnel = new Tunnel(); tunnel.setId(1L); tunnel.setType(1);
                return tunnel;
            }).when(service).getById(1L);
            doAnswer(inv -> {
                Tunnel tunnel = inv.getArgument(0);
                jdbc.update("UPDATE tunnel SET name=? WHERE id=1",tunnel.getName());
                return true;
            }).when(service).updateById(any(Tunnel.class));
            var nodes = mock(NodeService.class);
            ReflectionTestUtils.setField(service,"nodeService",nodes);
            List<Future<Integer>> writes = new ArrayList<>();
            when(nodes.list(any())).thenAnswer(inv -> {
                CountDownLatch started = new CountDownLatch(1);
                Future<Integer> heartbeat = worker.submit(() -> {
                    started.countDown();
                    return jdbc.update("UPDATE node SET status=2 WHERE id=2");
                });
                writes.add(heartbeat);
                assertTrue(started.await(5,TimeUnit.SECONDS));
                try { heartbeat.get(300,TimeUnit.MILLISECONDS); }
                catch (TimeoutException expected) { /* 保存先持有写锁，心跳等待提交。 */ }
                Node node = new Node(); node.setId(2L); node.setStatus(1); node.setServerIp("192.0.2.2");
                return List.of(node);
            });
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":1,\"role_id\":0}".getBytes())+".s");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            ChainTunnel entry = new ChainTunnel(); entry.setNodeId(2L);
            TunnelUpdateDto dto = new TunnelUpdateDto(); dto.setId(1L); dto.setFlow(1); dto.setName("new"); dto.setInNodeId(List.of(entry));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            assertEquals(0,tx.execute(status -> service.updateTunnel(dto)).getCode());
            assertEquals(1,writes.get(0).get(5,TimeUnit.SECONDS));
            assertEquals("new",jdbc.queryForObject("SELECT name FROM tunnel WHERE id=1",String.class));
            assertEquals(2,jdbc.queryForObject("SELECT status FROM node WHERE id=2",Integer.class));
            verify(chains).saveBatch(argThat(entries -> entries.size()==1));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            worker.shutdownNow();
        }
    }
}
