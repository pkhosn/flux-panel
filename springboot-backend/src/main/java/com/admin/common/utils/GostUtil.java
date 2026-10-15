package com.admin.common.utils;

import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.entity.*;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class GostUtil {


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "AddLimiters");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        GostDto gostDto = WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChains(Long node_id, List<ChainTunnel> chainTunnels, Map<Long, Node> node_s) {
        JSONArray nodes = new JSONArray();
        for (ChainTunnel chainTunnel : chainTunnels) {
            JSONObject dialer = new JSONObject();
            dialer.put("type", chainTunnel.getProtocol());

            JSONObject connector = new JSONObject();
            connector.put("type", "relay");

            Node node_info = node_s.get(chainTunnel.getNodeId());
            JSONObject node = new JSONObject();
            node.put("name", "node_" + chainTunnel.getInx());
            node.put("addr", processServerAddress(node_info.getServerIp() + ":" + chainTunnel.getPort()));
            node.put("connector", connector);
            node.put("dialer", dialer);



            nodes.add(node);
        }
        JSONObject hop = new JSONObject();
        hop.put("name", "hop_" + chainTunnels.getFirst().getTunnelId());

        // interface设置在转发链
        if (StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            hop.put("interface", node_s.get(node_id).getInterfaceName());
        }


        JSONObject selector = new JSONObject();
        selector.put("strategy", chainTunnels.getFirst().getStrategy());
        selector.put("maxFails", 1);
        selector.put("failTimeout", 600000000000L); // 600 秒（纳秒单位）


        hop.put("selector", selector);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", "chains_" + chainTunnels.getFirst().getTunnelId());
        data.put("hops", hops);

        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "AddChains");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteChains");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddChainService(Long node_id, ChainTunnel chainTunnel, Map<Long, Node> node_s) {
        JSONArray services = new JSONArray();
        Node node_info = node_s.get(chainTunnel.getNodeId());
        JSONObject service_item = new JSONObject();
        service_item.put("name", chainTunnel.getTunnelId() + "_tls");
        service_item.put("addr", node_info.getTcpListenAddr() + ":" + chainTunnel.getPort());
        
        // 只为出口节点(chainType=3)设置 interface
        if (chainTunnel.getChainType() == 3 && StringUtils.isNotBlank(node_s.get(node_id).getInterfaceName())) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", node_s.get(node_id).getInterfaceName());
            service_item.put("metadata", metadata);
        }

        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        if (chainTunnel.getChainType() == 2){
            handler.put("chain","chains_" + chainTunnel.getTunnelId());
        }
        service_item.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", chainTunnel.getProtocol());
        service_item.put("listener", listener);

        services.add(service_item);

        GostDto gostDto = WebSocketServer.send_msg(node_id, services, "AddService");
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto AddAndUpdateService(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel, String meth) {
        JSONArray services = forwardServiceConfig(name, limiter, node, forward, forwardPort, tunnel);
        GostDto gostDto = WebSocketServer.send_msg(node.getId(), services, meth);
        if (gostDto.getMsg().contains("exists")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    static JSONArray forwardServiceConfig(String name, Integer limiter, Node node, Forward forward, ForwardPort forwardPort, Tunnel tunnel) {
        JSONArray services = new JSONArray();
        String[] protocols = forwardProtocols(forward.getProtocol());
        for (String protocol : protocols) {
            JSONObject service = new JSONObject();
            service.put("name", name + "_" + protocol);
            if (Objects.equals(protocol, "tcp")){
                service.put("addr", node.getTcpListenAddr() + ":" + forwardPort.getPort());
            }else {
                service.put("addr", node.getUdpListenAddr() + ":" + forwardPort.getPort());
            }

            // 只在端口转发时设置 interface（隧道转发时 interface 在转发链的节点上设置）
            if (tunnel.getType() == 1 && StringUtils.isNotBlank(node.getInterfaceName())) {
                JSONObject metadata = new JSONObject();
                metadata.put("interface", node.getInterfaceName());
                service.put("metadata", metadata);
            }

            // 添加限流器配置
            if (limiter != null) {
                service.put("limiter", limiter.toString());
            }

            // 配置处理器
            JSONObject handler = new JSONObject();
            handler.put("type", protocol);
            if (Objects.equals(protocol, "tcp") && forward.getProxySend() != null && forward.getProxySend() > 0) {
                JSONObject metadata = new JSONObject();
                metadata.put("proxyProtocol", forward.getProxySend());
                handler.put("metadata", metadata);
            }
            if ("tcp".equals(protocol) && Integer.valueOf(1).equals(forward.getOriginTls())) {
                JSONObject metadata = handler.getJSONObject("metadata");
                if (metadata == null) metadata = new JSONObject();
                metadata.put("originTLS", true);
                metadata.put("originServerName", forward.getOriginServerName());
                metadata.put("originRootCA", forward.getOriginRootCa());
                metadata.put("originALPN", forward.getOriginAlpn());
                handler.put("metadata", metadata);
            }
            if (tunnel.getType() == 2){
                handler.put("chain", "chains_" + forward.getTunnelId());
            }
            service.put("handler", handler);

            // 配置监听器
            JSONObject listener = createListener(protocol);
            if (Objects.equals(protocol, "tcp") && forward.getProxyAccept() != null && forward.getProxyAccept() > 0) {
                service.put("metadata", mergeProxyAccept(service.getJSONObject("metadata"), forward));
                JSONObject metadata = new JSONObject();
                metadata.put("proxyTrustedCIDRs", Arrays.stream(forward.getProxyTrustedCidrs().split(","))
                        .map(String::trim).toList());
                listener.put("metadata", metadata);
            }
            if (forward.getTlsInboundId() != null && forward.getTlsInboundId() > 0) {
                JSONObject metadata = listener.getJSONObject("metadata");
                if (metadata == null) metadata = new JSONObject();
                metadata.put("sniNames", Arrays.asList(forward.getSniNames().split(",")));
                listener.put("metadata", metadata);
            }
            service.put("listener", listener);

            JSONObject forwarder = createForwarder(forward.getRemoteAddr(), forward.getStrategy());
            service.put("forwarder", forwarder);

            services.add(service);
        }
        return services;
    }

    public static GostDto DeleteService(Long node_id, JSONArray services) {
        JSONObject data = new JSONObject();
        data.put("services", services);
        GostDto gostDto = WebSocketServer.send_msg(node_id, data, "DeleteService");
        if (gostDto.getMsg().contains("not found")){
            gostDto.setMsg("OK");
        }
        return gostDto;
    }

    public static GostDto PauseAndResumeService(Long node_id, String name, String meth) {
        return PauseAndResumeService(node_id, name, "tcp_udp", meth);
    }

    public static GostDto PauseAndResumeService(Long node_id, String name, String protocol, String meth) {
        JSONObject data = new JSONObject();
        data.put("services", forwardServiceNames(name, protocol));
        return WebSocketServer.send_msg(node_id, data, meth);
    }

    public static String normalizeForwardProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) return "tcp_udp";
        return switch (protocol) {
            case "tcp", "udp", "tcp_udp" -> protocol;
            default -> null;
        };
    }

    private static JSONObject mergeProxyAccept(JSONObject existing, Forward forward) {
        JSONObject metadata = existing == null ? new JSONObject() : existing;
        metadata.put("proxyProtocol", forward.getProxyAccept());
        return metadata;
    }

    public static String validateForwardProxy(String protocol, Integer accept, Integer send, String trustedCidrs) {
        int receiveVersion = accept == null ? 0 : accept;
        int sendVersion = send == null ? 0 : send;
        if (receiveVersion < 0 || receiveVersion > 1 || sendVersion < 0 || sendVersion > 2) {
            return "接收模式只能关闭或自动识别；发送模式只能关闭、v1 或 v2";
        }
        if ((receiveVersion != 0 || sendVersion != 0) && !"tcp".equals(protocol)) {
            return "Proxy Protocol 仅支持单 TCP 规则";
        }
        if (receiveVersion != 0 && (trustedCidrs == null || trustedCidrs.isBlank())) {
            return "接收 Proxy Protocol 时必须填写可信来源 CIDR";
        }
        if (trustedCidrs != null && !trustedCidrs.isBlank()) {
            for (String value : trustedCidrs.split(",")) {
                String cidr = value.trim();
                String[] parts = cidr.split("/", -1);
                if (parts.length != 2) return "可信来源 CIDR 格式无效";
                try {
                    if (!(parts[0].contains(":") || parts[0].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                        return "可信来源 CIDR 必须使用 IP 地址";
                    }
                    InetAddress address = InetAddress.getByName(parts[0]);
                    if (!parts[0].contains(":")) {
                        for (String octet : parts[0].split("\\.")) {
                            if (Integer.parseInt(octet) > 255) return "可信来源 CIDR 格式无效";
                        }
                    }
                    int prefix = Integer.parseInt(parts[1]);
                    if (prefix < 0 || prefix > address.getAddress().length * 8) return "可信来源 CIDR 前缀无效";
                } catch (UnknownHostException | NumberFormatException ex) {
                    return "可信来源 CIDR 格式无效";
                }
            }
        }
        return null;
    }

    public static String validateForwardOrigin(String protocol, Integer enabled, String serverName, String rootCa, String alpn) {
        if (enabled != null && enabled != 0 && enabled != 1) return "回源 TLS 开关无效";
        if (!Integer.valueOf(1).equals(enabled)) return null;
        if (!"tcp".equals(protocol)) return "回源 TLS 仅支持单 TCP 规则";
        if (serverName != null && !serverName.isBlank()
                && (serverName.length() > 253 || !serverName.matches("[a-zA-Z0-9._:-]+"))) return "TLS 校验名称无效";
        if (rootCa != null && !rootCa.isBlank()) {
            if (rootCa.length() > 65536) return "根证书不能超过 64 KiB";
            try {
                var certificates = java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificates(new java.io.ByteArrayInputStream(rootCa.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                if (certificates.isEmpty()) return "根证书 PEM 无效";
            } catch (java.security.cert.CertificateException ex) { return "根证书 PEM 无效"; }
        }
        if (alpn != null && !alpn.isBlank()) {
            if (alpn.length() > 1024) return "ALPN 过长";
            for (String item : alpn.split(",", -1)) {
                if (item.isBlank() || item.length() > 255 || !item.matches("[!-~]+")) return "ALPN 格式无效";
            }
        }
        return null;
    }

    public static boolean supportsForwardProxy(Node node, Forward forward) {
        if ((forward.getProxyAccept() == null || forward.getProxyAccept() == 0)
                && (forward.getProxySend() == null || forward.getProxySend() == 0)
                && !Integer.valueOf(1).equals(forward.getOriginTls())
                && (forward.getTlsInboundId() == null || forward.getTlsInboundId() == 0)) return true;
        String version = node.getVersion();
        if (version == null) return false;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)-beta$")
                .matcher(version);
        if (!match.matches()) return false;
        int major = Integer.parseInt(match.group(1));
        int minor = Integer.parseInt(match.group(2));
        int patch = Integer.parseInt(match.group(3));
        return major > 2 || (major == 2 && (minor > 0 || (minor == 0 && patch >= (forward.getTlsInboundId() != null && forward.getTlsInboundId() > 0 ? 14 : Integer.valueOf(1).equals(forward.getOriginTls()) ? 13 : 12))));
    }

    public static String[] forwardProtocols(String protocol) {
        String normalized = normalizeForwardProtocol(protocol);
        if ("tcp".equals(normalized)) return new String[]{"tcp"};
        if ("udp".equals(normalized)) return new String[]{"udp"};
        return new String[]{"tcp", "udp"};
    }

    public static JSONArray forwardServiceNames(String name, String protocol) {
        JSONArray services = new JSONArray();
        for (String selected : forwardProtocols(protocol)) services.add(name + "_" + selected);
        return services;
    }


    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")) {
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num);
            node.put("addr", addr);
            nodes.add(node);
            num++;
        }

        if (strategy == null || strategy.isEmpty()) {
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    public static String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }

        // 如果已经被方括号包裹，直接返回
        if (serverAddr.startsWith("[")) {
            return serverAddr;
        }

        // 查找最后一个冒号，分离主机和端口
        int lastColonIndex = serverAddr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            // 没有端口号，直接检查是否需要包裹
            return isIPv6Address(serverAddr) ? "[" + serverAddr + "]" : serverAddr;
        }

        String host = serverAddr.substring(0, lastColonIndex);
        String port = serverAddr.substring(lastColonIndex);

        // 检查主机部分是否为IPv6地址
        if (isIPv6Address(host)) {
            return "[" + host + "]" + port;
        }

        return serverAddr;
    }

    private static boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个
        if (!address.contains(":")) {
            return false;
        }

        // 计算冒号数量，IPv6地址至少有2个冒号
        long colonCount = address.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }
}
