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

        forward.setStrategy("least_connections");
        assertEquals("127.0.0.1:443", GostUtil.forwardServiceConfig("least", null, node, forward, port, tunnel)
                .getJSONObject(0).getJSONObject("handler").getJSONObject("metadata").getJSONArray("leastTargets").getString(0));
        forward.setOriginTls(1);
        forward.setOriginServerName("origin.example.com");
        forward.setOriginAlpn("h2,http/1.1");
        JSONObject tlsMetadata = GostUtil.forwardServiceConfig("tls-rule", null, node, forward, port, tunnel)
                .getJSONObject(0).getJSONObject("handler").getJSONObject("metadata");
        assertTrue(tlsMetadata.getBoolean("originTLS"));
        assertEquals("origin.example.com", tlsMetadata.getString("originServerName"));
        assertEquals(2, tlsMetadata.getInteger("proxyProtocol"));
        assertNotNull(GostUtil.validateForwardOrigin("udp", 1, "", "", ""));
        assertNotNull(GostUtil.validateForwardOrigin("tcp", 1, "", "invalid", ""));
        assertNull(GostUtil.validateForwardOrigin("tcp", 1, "origin.example.com", "", "h2,http/1.1"));
        node.setVersion("2.0.12-beta");
        assertFalse(GostUtil.supportsForwardProxy(node, forward));
        node.setVersion("2.0.13-beta");
        assertTrue(GostUtil.supportsForwardProxy(node, forward));
        forward.setTlsInboundId(1);
        forward.setSniNames("app.example.com,api.example.com");
        node.setVersion("2.0.13-beta");
        assertFalse(GostUtil.supportsForwardProxy(node, forward));
        node.setVersion("2.0.15-beta");
        assertTrue(GostUtil.supportsForwardProxy(node, forward));
        JSONObject sharedListener = GostUtil.forwardServiceConfig("shared", null, node, forward, port, tunnel)
                .getJSONObject(0).getJSONObject("listener");
        assertEquals(2, sharedListener.getJSONObject("metadata").getJSONArray("sniNames").size());
        forward.setTlsInboundId(0);
        forward.setOriginTls(0);
        forward.setProxyAccept(0);
        forward.setProxySend(0);
        forward.setProxyTrustedCidrs("");
        forward.setStrategy("round");
        JSONObject defaultService = GostUtil.forwardServiceConfig("rule", null, node, forward, port, tunnel)
                .getJSONObject(0);
        assertNull(defaultService.getJSONObject("metadata"));
        assertNull(defaultService.getJSONObject("handler").getJSONObject("metadata"));
    }
}
