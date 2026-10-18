package com.admin;
import com.admin.service.*;
import com.admin.controller.NodeLatencyController;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.alibaba.fastjson2.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeLatencyTest {
    static class Fake extends NodeLatencyService {
        int calls;
        protected JSONObject request(String path, JSONObject body) {
            calls++;
            if (body != null) { assertEquals(3,body.getJSONArray("locations").size()); return JSONObject.of("id","abc123"); }
            return JSONObject.of("status","finished","results",new JSONArray());
        }
    }
    @Test void manualClickStartsFreshTestButPendingRequestsDoNotDuplicate() {
        var service = new Fake();
        assertEquals("idle", service.measure("74.48.114.112",false).getString("status"));
        assertEquals(0,service.calls);
        assertEquals("pending",service.measure("74.48.114.112",true).getString("status"));
        service.measure("74.48.114.112",true);
        assertEquals(1,service.calls);
        assertEquals("finished",service.measure("74.48.114.112",false).getString("status"));
        assertEquals(2,service.calls);
        service.measure("74.48.114.112",false);
        assertEquals(2,service.calls);
        assertEquals("pending",service.measure("74.48.114.112",true).getString("status"));
        assertEquals(3,service.calls);
    }
    @Test void manualRetryAfterFailureDoesNotWaitForCacheExpiry() {
        var service = new NodeLatencyService() {
            int attempts;
            protected JSONObject request(String path, JSONObject body) {
                if (++attempts == 1) throw new IllegalStateException("unavailable");
                return JSONObject.of("id", "retry123");
            }
        };
        assertEquals("error",service.measure("74.48.114.112",true).getString("status"));
        assertEquals("pending",service.measure("74.48.114.112",true).getString("status"));
    }
    @Test void strictCarrierMatchingAndTimeoutIsNotZero() {
        var raw=JSONObject.parseObject("{\"results\":[{\"probe\":{\"country\":\"CN\",\"asn\":4134,\"city\":\"Dongguan\"},\"result\":{\"stats\":{\"avg\":80.5,\"loss\":0}}},{\"probe\":{\"country\":\"CN\",\"asn\":4837},\"result\":{\"stats\":{\"avg\":null,\"loss\":100}}},{\"probe\":{\"country\":\"US\",\"asn\":9808},\"result\":{\"stats\":{\"avg\":1}}}]}");
        var rows=NodeLatencyService.summarize(raw,"74.48.114.112",1).getJSONArray("results");
        assertEquals("ok",rows.getJSONObject(0).getString("status"));
        assertEquals(80.5,rows.getJSONObject(0).getDoubleValue("avg"));
        assertEquals("timeout",rows.getJSONObject(1).getString("status"));
        assertNull(rows.getJSONObject(1).get("avg"));
        assertEquals("unavailable",rows.getJSONObject(2).getString("status"));
    }
    @Test void onlyVisibleNodesMayBeTestedAndDetectedAddressPreferred() {
        var nodes=mock(NodeService.class);var service=mock(NodeLatencyService.class);
        var node=new Node();node.setId(7L);node.setServerIp("example.com");node.setDetectedIpv4("74.48.114.112");
        when(nodes.getAllNodes()).thenReturn(R.ok(List.of(node)));
        when(service.measure(anyString(),anyBoolean())).thenReturn(JSONObject.of("status","idle"));
        var controller=new NodeLatencyController(nodes,service);
        assertNotEquals(0,controller.latency(Map.of("id",8,"run",true)).getCode());
        verifyNoInteractions(service);
        assertEquals(0,controller.latency(Map.of("id",7,"run",true)).getCode());
        verify(service).measure("74.48.114.112",true);
    }
}
