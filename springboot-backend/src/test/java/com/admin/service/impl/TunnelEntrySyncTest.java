package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.*;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TunnelEntrySyncTest {
    @Test void onlyChangedEntryIsSyncedAndPortsOnRetainedEntriesStayUntouched() {
        for (String outcome : List.of("OK", "bind: address already in use")) {
        for (boolean adding : List.of(true, false)) {
            List<Long> synced = new ArrayList<>();
            TunnelServiceImpl service = new TunnelServiceImpl() {
                @Override protected GostDto syncEntryServices(Long node, JSONArray desired) {
                    synced.add(node); GostDto response = new GostDto(); response.setMsg(outcome); return response;
                }
            };
            var ports = mock(ForwardPortService.class);
            ForwardPort existing = new ForwardPort(); existing.setId(11L); existing.setForwardId(398L); existing.setNodeId(1L); existing.setPort(22166);
            ForwardPort removed = new ForwardPort(); removed.setId(12L); removed.setForwardId(398L); removed.setNodeId(2L); removed.setPort(22166);
            when(ports.list(any())).thenAnswer(inv -> {
                QueryWrapper<?> query = inv.getArgument(0);
                return query.getSqlSegment().contains("node_id") ? List.of() : adding ? List.of(existing) : List.of(existing, removed);
            });
            when(ports.save(any())).thenReturn(true);
            ReflectionTestUtils.setField(service,"forwardPortService",ports);
            var chains = mock(ChainTunnelService.class);
            when(chains.list(any())).thenReturn(List.of());
            when(chains.saveBatch(anyCollection())).thenReturn(true);
            ReflectionTestUtils.setField(service,"chainTunnelService",chains);
            var forwards = mock(ForwardService.class);
            when(forwards.recoveryServices(Set.of(2L))).thenReturn(Map.of(2L,new JSONArray()));
            ReflectionTestUtils.setField(service,"forwardService",forwards);
            var nodes = mock(NodeService.class);
            Node node = new Node(); node.setId(2L); node.setPort("22166-22168");
            when(nodes.getById(2L)).thenReturn(node);
            ReflectionTestUtils.setField(service,"nodeService",nodes);
            var jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(),eq(Integer.class),eq(2L))).thenReturn(List.of());
            ReflectionTestUtils.setField(service,"tlsJdbc",jdbc);
            Tunnel tunnel = new Tunnel(); tunnel.setId(26L);
            ChainTunnel first = new ChainTunnel(); first.setNodeId(1L);
            ChainTunnel second = new ChainTunnel(); second.setNodeId(2L);
            Forward rule = new Forward(); rule.setId(398L); rule.setName("test");
            R result = ReflectionTestUtils.invokeMethod(service,"syncPortTunnelEntries",tunnel,
                    adding ? List.of(first) : List.of(first,second), adding ? List.of(first,second) : List.of(first), List.of(rule));
            if ("OK".equals(outcome)) assertEquals(0,result.getCode());
            else { assertNotEquals(0,result.getCode()); assertTrue(result.getMsg().contains(outcome)); }
            assertEquals(List.of(2L),synced);
            verify(ports,never()).removeById(11L);
            if (adding) verify(ports).save(argThat(port -> port.getNodeId()==2L && port.getPort()==22166));
            else verify(ports).removeById(12L);
            verify(forwards).recoveryServices(Set.of(2L));
        }
        }
    }
}
