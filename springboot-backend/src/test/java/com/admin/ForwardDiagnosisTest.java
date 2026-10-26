package com.admin;

import com.admin.common.dto.GostDto;
import com.admin.entity.*;
import com.admin.service.*;
import com.admin.service.impl.ForwardServiceImpl;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardDiagnosisTest {
    @Test void probesRunConcurrentlyAndPreserveFailedResultsAndOrder() {
        CountDownLatch concurrent = new CountDownLatch(2);
        var service = spy(new ForwardServiceImpl() {
            @Override protected GostDto sendDiagnosis(Long node, JSONObject data) {
                assertEquals(2,data.getIntValue("count"));
                assertEquals(1500,data.getIntValue("timeout"));
                concurrent.countDown();
                try { assertTrue(concurrent.await(3,TimeUnit.SECONDS),"probes were serialized"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                GostDto result = new GostDto();
                result.setMsg(node==1 ? "OK" : "节点不在线");
                if (node==1) {
                    JSONObject response = new JSONObject(); response.put("success",true); response.put("averageTime",100);
                    result.setData(response);
                }
                return result;
            }
        });
        Forward rule = new Forward(); rule.setId(1L); rule.setTunnelId(26); rule.setName("rule"); rule.setUserId(1); rule.setRemoteAddr("192.0.2.1:443");
        doReturn(rule).when(service).getById(1L);
        var tunnels = mock(TunnelService.class); Tunnel tunnel = new Tunnel(); tunnel.setId(26L); tunnel.setType(1);
        when(tunnels.getById(26)).thenReturn(tunnel); ReflectionTestUtils.setField(service,"tunnelService",tunnels);
        var chains = mock(ChainTunnelService.class); var first = new ChainTunnel(); first.setNodeId(1L); first.setChainType(1);
        var second = new ChainTunnel(); second.setNodeId(2L); second.setChainType(1);
        when(chains.list(any())).thenReturn(List.of(first,second)); ReflectionTestUtils.setField(service,"chainTunnelService",chains);
        var nodes = mock(NodeService.class);
        for (long id : List.of(1L,2L)) { var node = new Node(); node.setId(id); node.setName("node-"+id); when(nodes.getById(id)).thenReturn(node); }
        ReflectionTestUtils.setField(service,"nodeService",nodes);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0,\"name\":\"admin\"}".getBytes())+".s");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var result = service.diagnoseForward(1L); assertEquals(0,result.getCode());
            var rows = (List<ForwardServiceImpl.DiagnosisResult>)((Map<?,?>)result.getData()).get("results");
            assertEquals(2,rows.size()); assertEquals(1L,rows.get(0).getNodeId());
            assertTrue(rows.get(0).isSuccess()); assertFalse(rows.get(1).isSuccess());
            assertEquals("节点不在线",rows.get(1).getMessage());
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
