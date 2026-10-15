package com.admin.common.utils;

import com.admin.entity.Forward;
import com.admin.entity.ForwardPort;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GostUtilForwardProxyTest {
    @Test
    void sendsProxySettingsToExpectedAgentFields() {
        Node node = new Node();
        node.setTcpListenAddr("127.0.0.1");
        node.setUdpListenAddr("127.0.0.1");
        ForwardPort port = new ForwardPort();
        port.setPort(12345);
        Tunnel tunnel = new Tunnel();
        tunnel.setType(1);
        Forward forward = new Forward();
        forward.setTunnelId(1);
        forward.setProtocol("tcp");
        forward.setRemoteAddr("127.0.0.1:443");
        forward.setProxyAccept(1);
        forward.setProxySend(2);
        forward.setProxyTrustedCidrs("127.0.0.0/8, ::1/128");

        JSONArray services = GostUtil.forwardServiceConfig("rule", null, node, forward, port, tunnel);
        assertEquals(1, services.size());
        JSONObject service = services.getJSONObject(0);
        assertEquals(1, service.getJSONObject("metadata").getInteger("proxyProtocol"));
        assertEquals(2, service.getJSONObject("handler").getJSONObject("metadata").getInteger("proxyProtocol"));
        assertEquals("127.0.0.0/8", service.getJSONObject("listener").getJSONObject("metadata")
                .getJSONArray("proxyTrustedCIDRs").getString(0));
        assertEquals("::1/128", service.getJSONObject("listener").getJSONObject("metadata")
                .getJSONArray("proxyTrustedCIDRs").getString(1));

        forward.setProxyAccept(0);
        forward.setProxySend(0);
        forward.setProxyTrustedCidrs("");
        JSONObject defaultService = GostUtil.forwardServiceConfig("rule", null, node, forward, port, tunnel)
                .getJSONObject(0);
        assertNull(defaultService.getJSONObject("metadata"));
        assertNull(defaultService.getJSONObject("handler").getJSONObject("metadata"));
    }
}
