package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.ForwardSni;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.ForwardMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 端口转发服务实现类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Slf4j
@Service
public class ForwardServiceImpl extends ServiceImpl<ForwardMapper, Forward> implements ForwardService {

    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    private static final Object IMPORT_LOCK = new Object();

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    UserTunnelService userTunnelService;

    @Resource
    UserService userService;

    @Resource
    NodeService nodeService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    ForwardPortService forwardPortService;

    @Resource
    JdbcTemplate jdbc;

    @Override
    public R getAllForwards() {
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardWithTunnelDto> forwardList;
        if (currentUser.getRoleId() != 0) {
            forwardList = baseMapper.selectForwardsWithTunnelByUserId(currentUser.getUserId());
        } else {
            forwardList = baseMapper.selectAllForwardsWithTunnel();
        }

        // 填充入口IP和端口信息
        for (ForwardWithTunnelDto forward : forwardList) {
            // 获取隧道信息
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) continue;

            // 获取该转发的所有ForwardPort记录
            List<ForwardPort> forwardPorts = forwardPortService.list(
                    new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId())
            );

            if (forwardPorts.isEmpty()) continue;

            // 判断是否使用隧道的inIp
            boolean useTunnelInIp = tunnel.getInIp() != null && !tunnel.getInIp().trim().isEmpty();

            Set<String> ipPortSet = new LinkedHashSet<>();

            if (useTunnelInIp) {
                // 使用隧道的inIp（求笛卡尔积）
                List<String> ipList = new ArrayList<>();
                List<Integer> portList = new ArrayList<>();
                
                String[] tunnelInIps = tunnel.getInIp().split(",");
                for (String ip : tunnelInIps) {
                    if (ip != null && !ip.trim().isEmpty()) {
                        ipList.add(ip.trim());
                    }
                }
                
                // 收集所有端口
                for (ForwardPort forwardPort : forwardPorts) {
                    if (forwardPort.getPort() != null) {
                        portList.add(forwardPort.getPort());
                    }
                }
                
                // 去重
                List<String> uniqueIps = ipList.stream().distinct().toList();
                List<Integer> uniquePorts = portList.stream().distinct().toList();
                
                // 组合 IP:Port（笛卡尔积）
                for (String ip : uniqueIps) {
                    for (Integer port : uniquePorts) {
                        ipPortSet.add(GostUtil.processServerAddress(ip + ":" + port));
                    }
                }
                
                // inPort设置为第一个端口（用于向后兼容）
                if (!uniquePorts.isEmpty()) {
                    forward.setInPort(uniquePorts.getFirst());
                }
            } else {
                // 使用节点的serverIp（一对一，不求笛卡尔积）
                for (ForwardPort forwardPort : forwardPorts) {
                    Node node = nodeService.getById(forwardPort.getNodeId());
                    if (node != null && node.getServerIp() != null && !node.getServerIp().isBlank() && forwardPort.getPort() != null) {
                        ipPortSet.add(GostUtil.processServerAddress(node.getServerIp() + ":" + forwardPort.getPort()));
                    }
                }
                
                // inPort设置为第一个端口（用于向后兼容）
                if (!forwardPorts.isEmpty() && forwardPorts.getFirst().getPort() != null) {
                    forward.setInPort(forwardPorts.getFirst().getPort());
                }
            }

            // 设置入口IP
            if (!ipPortSet.isEmpty()) {
                forward.setInIp(String.join(",", ipPortSet));
            }
        }

        return R.ok(forwardList);
    }

    @Override
    public synchronized R createForward(ForwardDto forwardDto) {
        UserInfo currentUser = getCurrentUserInfo();

        Tunnel tunnel = validateTunnel(forwardDto.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        if (tunnel.getStatus() != 1) {
            return R.err("隧道已禁用，无法创建转发");
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        Forward forward = new Forward();
        BeanUtils.copyProperties(forwardDto, forward);
        String protocol = GostUtil.normalizeForwardProtocol(forwardDto.getProtocol());
        if (protocol == null) return R.err("转发协议无效");
        String proxyError = GostUtil.validateForwardProxy(protocol, forwardDto.getProxyAccept(),
                forwardDto.getProxySend(), forwardDto.getProxyTrustedCidrs());
        if (proxyError != null) return R.err(proxyError);
        String originError = GostUtil.validateForwardOrigin(protocol, forwardDto.getOriginTls(), forwardDto.getOriginServerName(), forwardDto.getOriginRootCa(), forwardDto.getOriginAlpn());
        if (originError != null) return R.err(originError);
        if (!validGroup(forwardDto.getGroupId(), currentUser.getUserId())) return R.err("分组不存在或无权使用");
        forward.setTlsInboundId(forwardDto.getTlsInboundId() == null ? 0 : forwardDto.getTlsInboundId());
        forward.setSniNames(forwardDto.getSniNames() == null ? "" : forwardDto.getSniNames());
        forward.setOriginTls(forwardDto.getOriginTls() == null ? 0 : forwardDto.getOriginTls());
        forward.setOriginServerName(forwardDto.getOriginServerName() == null ? "" : forwardDto.getOriginServerName());
        forward.setOriginRootCa(forwardDto.getOriginRootCa() == null ? "" : forwardDto.getOriginRootCa());
        forward.setOriginAlpn(forwardDto.getOriginAlpn() == null ? "" : forwardDto.getOriginAlpn());
        forward.setProtocol(protocol);
        forward.setProxyAccept(forwardDto.getProxyAccept() == null ? 0 : forwardDto.getProxyAccept());
        forward.setProxySend(forwardDto.getProxySend() == null ? 0 : forwardDto.getProxySend());
        forward.setProxyTrustedCidrs(forwardDto.getProxyTrustedCidrs() == null ? "" : forwardDto.getProxyTrustedCidrs());
        forward.setStatus(1);
        forward.setUserId(currentUser.getUserId());
        forward.setUserName(currentUser.getUserName());
        forward.setCreatedTime(System.currentTimeMillis());
        forward.setUpdatedTime(System.currentTimeMillis());
        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        if (chainTunnels.isEmpty()) return R.err("隧道未配置入口节点");
        try { chainTunnels = allocateForwardPorts(forward, chainTunnels, forwardDto.getInPort(), 0L); }
        catch (IllegalArgumentException ex) { return R.err(ex.getMessage()); }
        Map<Long, Node> entryNodes = new HashMap<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) return R.err("部分节点不存在");
            if (!GostUtil.supportsForwardProxy(node, forward)) return R.err("节点 " + node.getName() + " 需升级 Agent 后才能使用 Proxy Protocol/TLS 新功能");
            entryNodes.put(node.getId(), node);
        }
        if (!this.save(forward)) return R.err("保存规则失败");
        String serviceName = buildServiceName(forward.getId(), forward.getUserId(), permissionResult.getUserTunnel());
        List<Long> attemptedNodes = new ArrayList<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            ForwardPort forwardPort = new ForwardPort();
            forwardPort.setForwardId(forward.getId());
            forwardPort.setNodeId(chainTunnel.getNodeId());
            forwardPort.setPort(chainTunnel.getPort());
            if (!forwardPortService.save(forwardPort)) {
                return cleanupFailedForwardCreate(forward, serviceName, attemptedNodes, "保存入口端口失败");
            }
            Node node = entryNodes.get(chainTunnel.getNodeId());
            attemptedNodes.add(node.getId());
            GostDto gostDto = GostUtil.AddAndUpdateService(serviceName, permissionResult.getLimiter(),
                    node, forward, forwardPort, tunnel, "AddService");
            if (!Objects.equals(gostDto.getMsg(), "OK")) {
                return cleanupFailedForwardCreate(forward, serviceName, attemptedNodes, gostDto.getMsg());
            }
        }
        return R.ok(forward.getId());
    }

    @Override
    public synchronized R importForwards(ForwardImportDto input) {
        if (input == null || input.getRules() == null || input.getRules().isEmpty()
                || input.getRules().size() > 500) return R.err("每次导入 1–500 条规则");
        UserInfo currentUser = getCurrentUserInfo();
        List<ForwardDto> rules = input.getRules();
        synchronized (IMPORT_LOCK) {
            if (currentUser.getRoleId() != 0) {
                User user = userService.getById(currentUser.getUserId());
                long existing = this.count(new QueryWrapper<Forward>().eq("user_id", currentUser.getUserId()));
                if (user == null || existing + rules.size() > user.getNum()) return R.err("导入后将超过用户规则数量限制");
            }
            for (int i = 0; i < rules.size(); i++) {
                ForwardDto rule = rules.get(i);
                if (rule == null || rule.getTunnelId() == null || rule.getName() == null
                        || rule.getName().isBlank() || rule.getRemoteAddr() == null
                        || rule.getRemoteAddr().isBlank()) {
                    return R.err("第 " + (i + 1) + " 条规则参数不完整");
                }
                if (rule.getInPort() != null && (rule.getInPort() < 1 || rule.getInPort() > 65535)) {
                    return R.err("第 " + (i + 1) + " 条入口端口无效");
                }
                for (String target : rule.getRemoteAddr().split(",", -1)) {
                    java.util.regex.Matcher address = java.util.regex.Pattern
                            .compile("^(?:\\[[0-9a-fA-F:]+\\]|[^\\s:,/]+):([0-9]{1,5})$").matcher(target.trim());
                    if (!address.matches() || Integer.parseInt(address.group(1)) < 1
                            || Integer.parseInt(address.group(1)) > 65535) {
                        return R.err("第 " + (i + 1) + " 条目标地址格式无效");
                    }
                }
                if (rule.getStatus() != null && rule.getStatus() != 0 && rule.getStatus() != 1) return R.err("规则状态无效");
                if (rule.getTlsInboundPort() != null && rule.getTlsInboundPort() > 0) {
                    List<Integer> mapped = jdbc.queryForList("SELECT id FROM forward_tls_inbound WHERE tunnel_id=? AND port=?",
                            Integer.class, rule.getTunnelId(), rule.getTlsInboundPort());
                    if (mapped.size() != 1) return R.err("请先在目标隧道创建端口 " + rule.getTlsInboundPort() + " 的共享 TLS 入口");
                    rule.setTlsInboundId(mapped.getFirst());
                }
                String protocol = GostUtil.normalizeForwardProtocol(rule.getProtocol());
                if (protocol == null) return R.err("第 " + (i + 1) + " 条协议无效");
                String proxyError = GostUtil.validateForwardProxy(protocol, rule.getProxyAccept(),
                        rule.getProxySend(), rule.getProxyTrustedCidrs());
                if (proxyError != null) return R.err("第 " + (i + 1) + " 条：" + proxyError);
                String originError = GostUtil.validateForwardOrigin(protocol, rule.getOriginTls(), rule.getOriginServerName(), rule.getOriginRootCa(), rule.getOriginAlpn());
                if (originError != null) return R.err("第 " + (i + 1) + " 条：" + originError);
                if (!validGroup(rule.getGroupId(), currentUser.getUserId())) {
                    return R.err("第 " + (i + 1) + " 条分组不存在或无权使用");
                }
                Tunnel tunnel = validateTunnel(rule.getTunnelId());
                if (tunnel == null || tunnel.getStatus() != 1) {
                    return R.err("第 " + (i + 1) + " 条隧道不存在或已禁用");
                }
                if (chainTunnelService.count(new QueryWrapper<ChainTunnel>()
                        .eq("tunnel_id", tunnel.getId()).eq("chain_type", 1)) == 0) {
                    return R.err("第 " + (i + 1) + " 条隧道没有入口节点");
                }
                UserPermissionResult permission = checkUserPermissions(currentUser, tunnel, null);
                if (permission.isHasError()) {
                    return R.err("第 " + (i + 1) + " 条：" + permission.getErrorMessage());
                }
                if (currentUser.getRoleId() != 0) {
                    long incoming = rules.stream().filter(item -> item != null
                            && Objects.equals(item.getTunnelId(), rule.getTunnelId())).count();
                    long existing = this.count(new QueryWrapper<Forward>().eq("user_id", currentUser.getUserId())
                            .eq("tunnel_id", rule.getTunnelId()));
                    if (existing + incoming > permission.getUserTunnel().getNum()) {
                        return R.err("导入后将超过隧道规则数量限制");
                    }
                }
            }
            Map<Long, Set<Integer>> reservedPorts = new HashMap<>();
            Map<Integer, Set<String>> importedSni = new HashMap<>();
            for (int i = 0; i < rules.size(); i++) {
                ForwardDto rule = rules.get(i);
                List<ChainTunnel> entries = chainTunnelService.list(new QueryWrapper<ChainTunnel>()
                        .eq("tunnel_id", rule.getTunnelId()).eq("chain_type", 1));
                Forward proposedRule = new Forward();
                BeanUtils.copyProperties(rule, proposedRule);
                boolean shared = proposedRule.getTlsInboundId() != null && proposedRule.getTlsInboundId() > 0;
                if (shared) {
                    try { allocateForwardPorts(proposedRule, entries, rule.getInPort(), 0L); }
                    catch (IllegalArgumentException ex) { return R.err("第 " + (i + 1) + " 条：" + ex.getMessage()); }
                    Set<String> names = importedSni.computeIfAbsent(proposedRule.getTlsInboundId(), key -> new HashSet<>());
                    for (String name : proposedRule.getSniNames().split(",")) {
                        if (!names.add(name)) return R.err("导入规则 SNI 重复：" + name);
                    }
                    rule.setSniNames(proposedRule.getSniNames());
                }
                for (ChainTunnel entry : entries) {
                    Forward proposed = new Forward();
                    BeanUtils.copyProperties(rule, proposed);
                    Node node = nodeService.getById(entry.getNodeId());
                    if (node == null || !GostUtil.supportsForwardProxy(node, proposed)) {
                        return R.err("第 " + (i + 1) + " 条入口节点不存在或需要升级 Agent");
                    }
                    if (shared) continue;
                    Set<Integer> reserved = reservedPorts.computeIfAbsent(entry.getNodeId(), id -> new HashSet<>());
                    List<Integer> available = getNodePort(entry.getNodeId(), 0L);
                    Integer selected = rule.getInPort();
                    if (selected == null) {
                        selected = available.stream().filter(port -> !reserved.contains(port)).findFirst().orElse(null);
                    }
                    if (selected == null || !available.contains(selected) || !reserved.add(selected)) {
                        return R.err("第 " + (i + 1) + " 条没有可用入口端口");
                    }
                }
            }
            List<Long> created = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                R result;
                try {
                    result = createForward(rules.get(i));
                    if (result.getCode() == 0 && Integer.valueOf(0).equals(rules.get(i).getStatus())) {
                        Long newId = ((Number) result.getData()).longValue();
                        R paused = pauseForward(newId);
                        if (paused.getCode() != 0) {
                            created.add(newId);
                            result = R.err("保留暂停状态失败：" + paused.getMsg());
                        }
                    }
                } catch (RuntimeException ex) {
                    log.error("导入第 {} 条规则失败", i + 1, ex);
                    result = R.err("创建规则异常，请检查节点端口和服务状态");
                }
                if (result.getCode() != 0) {
                    List<Long> cleanupFailed = new ArrayList<>();
                    Collections.reverse(created);
                    for (Long id : created) {
                        try {
                            R removed = deleteForward(id);
                            if (removed.getCode() != 0) cleanupFailed.add(id);
                        } catch (RuntimeException ex) {
                            log.error("导入回退规则 {} 失败", id, ex);
                            cleanupFailed.add(id);
                        }
                    }
                    return R.err("第 " + (i + 1) + " 条创建失败：" + result.getMsg()
                            + (cleanupFailed.isEmpty() ? "；此前导入的规则已清理"
                            : "；清理失败的规则 ID：" + cleanupFailed));
                }
                created.add(((Number) result.getData()).longValue());
            }
            return R.ok(Map.of("count", created.size()));
        }
    }

    private R cleanupFailedForwardCreate(Forward forward, String serviceName,
                                         List<Long> attemptedNodes, String error) {
        List<String> rollbackFailures = new ArrayList<>();
        Collections.reverse(attemptedNodes);
        for (Long nodeId : attemptedNodes) {
            GostDto result = GostUtil.DeleteService(nodeId,
                    GostUtil.forwardServiceNames(serviceName, forward.getProtocol()));
            if (!Objects.equals(result.getMsg(), "OK")) rollbackFailures.add(String.valueOf(nodeId));
        }
        if (!rollbackFailures.isEmpty()) {
            return R.err(error + "；节点清理失败：" + String.join(",", rollbackFailures)
                    + "；已保留规则 " + forward.getId() + " 供诊断和强制删除");
        }
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", forward.getId()));
        this.removeById(forward.getId());
        return R.err(error);
    }

    @Override
    public synchronized R updateForward(ForwardUpdateDto forwardUpdateDto) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 检查转发是否存在
        Forward existForward = validateForwardExists(forwardUpdateDto.getId(), currentUser);
        if (existForward == null) {
            return R.err("转发不存在");
        }
        if (!Objects.equals(existForward.getStatus(), 1)) {
            return R.err("暂停中的规则请先恢复，再编辑或切换入口");
        }

        Integer targetTunnelId = forwardUpdateDto.getTunnelId() != null
                ? forwardUpdateDto.getTunnelId()
                : existForward.getTunnelId();
        boolean tunnelChanged = !Objects.equals(existForward.getTunnelId(), targetTunnelId);
        String previousProtocol = GostUtil.normalizeForwardProtocol(existForward.getProtocol());
        String nextProtocol = forwardUpdateDto.getProtocol() == null
                ? previousProtocol : GostUtil.normalizeForwardProtocol(forwardUpdateDto.getProtocol());
        if (nextProtocol == null) return R.err("转发协议无效");
        Integer nextProxyAccept = forwardUpdateDto.getProxyAccept() == null
                ? existForward.getProxyAccept() : forwardUpdateDto.getProxyAccept();
        Integer nextProxySend = forwardUpdateDto.getProxySend() == null
                ? existForward.getProxySend() : forwardUpdateDto.getProxySend();
        String nextTrustedCidrs = forwardUpdateDto.getProxyTrustedCidrs() == null
                ? existForward.getProxyTrustedCidrs() : forwardUpdateDto.getProxyTrustedCidrs();
        String proxyError = GostUtil.validateForwardProxy(nextProtocol, nextProxyAccept, nextProxySend, nextTrustedCidrs);
        if (proxyError != null) return R.err(proxyError);
        Integer nextInbound = forwardUpdateDto.getTlsInboundId() == null ? existForward.getTlsInboundId() : forwardUpdateDto.getTlsInboundId();
        boolean recreateServices = tunnelChanged || !Objects.equals(previousProtocol, nextProtocol)
                || !Objects.equals(nextInbound, existForward.getTlsInboundId());

        Tunnel tunnel = validateTunnel(targetTunnelId);
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, existForward.getId());
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }

        UserTunnel newUserTunnel;
        if (currentUser.getRoleId() != 0) {
            newUserTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (newUserTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            newUserTunnel = getUserTunnel(existForward.getUserId(), tunnel.getId().intValue());
        }

        UserTunnel oldUserTunnel = null;
        Tunnel oldTunnel = null;
        if (recreateServices) {
            oldTunnel = validateTunnel(existForward.getTunnelId());
            if (oldTunnel != null) {
                oldUserTunnel = getUserTunnel(existForward.getUserId(), oldTunnel.getId().intValue());
            }
        }

        Forward previousForward = new Forward();
        BeanUtils.copyProperties(existForward, previousForward);
        existForward.setRemoteAddr(forwardUpdateDto.getRemoteAddr());
        if (forwardUpdateDto.getGroupId() != null) {
            if (!validGroup(forwardUpdateDto.getGroupId(), existForward.getUserId())) return R.err("分组不存在或无权使用");
            existForward.setGroupId(forwardUpdateDto.getGroupId() == 0 ? null : forwardUpdateDto.getGroupId());
        }
        Integer originTls = forwardUpdateDto.getOriginTls() == null ? existForward.getOriginTls() : forwardUpdateDto.getOriginTls();
        String originName = forwardUpdateDto.getOriginServerName() == null ? existForward.getOriginServerName() : forwardUpdateDto.getOriginServerName();
        String originCa = forwardUpdateDto.getOriginRootCa() == null ? existForward.getOriginRootCa() : forwardUpdateDto.getOriginRootCa();
        String originAlpn = forwardUpdateDto.getOriginAlpn() == null ? existForward.getOriginAlpn() : forwardUpdateDto.getOriginAlpn();
        String originError = GostUtil.validateForwardOrigin(nextProtocol, originTls, originName, originCa, originAlpn);
        if (originError != null) return R.err(originError);
        existForward.setOriginTls(originTls);
        existForward.setOriginServerName(originName);
        existForward.setOriginRootCa(originCa);
        existForward.setOriginAlpn(originAlpn);
        existForward.setTlsInboundId(nextInbound == null ? 0 : nextInbound);
        if (forwardUpdateDto.getSniNames() != null) existForward.setSniNames(forwardUpdateDto.getSniNames());
        existForward.setProtocol(nextProtocol);
        existForward.setProxyAccept(nextProxyAccept == null ? 0 : nextProxyAccept);
        existForward.setProxySend(nextProxySend == null ? 0 : nextProxySend);
        existForward.setProxyTrustedCidrs(nextTrustedCidrs == null ? "" : nextTrustedCidrs);
        existForward.setName(forwardUpdateDto.getName());
        existForward.setStrategy(forwardUpdateDto.getStrategy());
        existForward.setTunnelId(targetTunnelId);
        existForward.setStatus(1);

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        if (chainTunnels.isEmpty()) return R.err("目标隧道未配置入口节点");

        // 自己占用的应该不算
        try { chainTunnels = allocateForwardPorts(existForward, chainTunnels, forwardUpdateDto.getInPort(), existForward.getId()); }
        catch (IllegalArgumentException ex) { return R.err(ex.getMessage()); }
        Map<Long, Node> entryNodes = new HashMap<>();
        for (ChainTunnel chainTunnel : chainTunnels) {
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) return R.err("部分节点不存在");
            if (!GostUtil.supportsForwardProxy(node, existForward)) return R.err("节点 " + node.getName() + " 需升级 Agent 后才能使用 Proxy Protocol/TLS 新功能");
            entryNodes.put(node.getId(), node);
        }

        String newServiceName = buildServiceName(existForward.getId(), existForward.getUserId(), newUserTunnel);

        if (!recreateServices) {
            Map<Long, ForwardPort> oldPorts = forwardPortService.list(
                    new QueryWrapper<ForwardPort>().eq("forward_id", existForward.getId())
            ).stream().collect(Collectors.toMap(ForwardPort::getNodeId, port -> port));
            List<ChainTunnel> attempted = new ArrayList<>();
            String failure = null;
            for (ChainTunnel chainTunnel : chainTunnels) {
                Node node = entryNodes.get(chainTunnel.getNodeId());
                ForwardPort proposed = new ForwardPort();
                proposed.setForwardId(existForward.getId());
                proposed.setNodeId(node.getId());
                proposed.setPort(chainTunnel.getPort());
                String method = oldPorts.containsKey(node.getId()) ? "UpdateService" : "AddService";
                attempted.add(chainTunnel);
                GostDto result = GostUtil.AddAndUpdateService(newServiceName, permissionResult.getLimiter(),
                        node, existForward, proposed, tunnel, method);
                if (!Objects.equals(result.getMsg(), "OK")) {
                    failure = result.getMsg();
                    break;
                }
            }
            if (failure != null) {
                List<String> rollbackFailures = new ArrayList<>();
                Collections.reverse(attempted);
                for (ChainTunnel chainTunnel : attempted) {
                    Node node = entryNodes.get(chainTunnel.getNodeId());
                    ForwardPort oldPort = oldPorts.get(node.getId());
                    GostDto restored;
                    if (oldPort == null) {
                        restored = GostUtil.DeleteService(node.getId(),
                                GostUtil.forwardServiceNames(newServiceName, previousProtocol));
                    } else {
                        restored = GostUtil.AddAndUpdateService(newServiceName, permissionResult.getLimiter(),
                                node, previousForward, oldPort, tunnel, "UpdateService");
                        if (!Objects.equals(restored.getMsg(), "OK")) {
                            restored = GostUtil.AddAndUpdateService(newServiceName, permissionResult.getLimiter(),
                                    node, previousForward, oldPort, tunnel, "AddService");
                        }
                    }
                    if (!Objects.equals(restored.getMsg(), "OK")) {
                        rollbackFailures.add(String.valueOf(node.getId()));
                    }
                }
                return R.err(rollbackFailures.isEmpty() ? failure
                        : failure + "；以下节点回滚失败：" + String.join(",", rollbackFailures));
            }
            this.updateById(existForward);
            for (ChainTunnel chainTunnel : chainTunnels) {
                ForwardPort port = oldPorts.get(chainTunnel.getNodeId());
                if (port == null) {
                    port = new ForwardPort();
                    port.setForwardId(existForward.getId());
                    port.setNodeId(chainTunnel.getNodeId());
                    port.setPort(chainTunnel.getPort());
                    forwardPortService.save(port);
                } else {
                    port.setPort(chainTunnel.getPort());
                    forwardPortService.updateById(port);
                }
            }
            return R.ok();
        }

        if (oldTunnel == null) return R.err("原隧道不存在，无法安全修改规则");
        List<ForwardPort> oldPorts = forwardPortService.list(
                new QueryWrapper<ForwardPort>().eq("forward_id", existForward.getId()));
        if (oldPorts.isEmpty()) return R.err("原入口端口记录缺失，无法安全修改规则");
        Map<Long, Node> oldNodes = new HashMap<>();
        for (ForwardPort oldPort : oldPorts) {
            Node node = nodeService.getById(oldPort.getNodeId());
            if (node == null) return R.err("原入口节点不存在，无法安全迁移规则");
            oldNodes.put(node.getId(), node);
        }
        String oldServiceName = buildServiceName(existForward.getId(), existForward.getUserId(), oldUserTunnel);
        Integer oldLimiter = oldUserTunnel == null ? null : oldUserTunnel.getSpeedId();
        List<ForwardPort> deletedOld = new ArrayList<>();
        List<ForwardPort> addedNew = new ArrayList<>();
        String failure = null;
        for (ForwardPort oldPort : oldPorts) {
            deletedOld.add(oldPort);
            GostDto result = GostUtil.DeleteService(oldPort.getNodeId(),
                    GostUtil.forwardServiceNames(oldServiceName, previousProtocol));
            if (!Objects.equals(result.getMsg(), "OK")) {
                failure = result.getMsg();
                break;
            }
        }
        if (failure == null) {
            for (ChainTunnel chainTunnel : chainTunnels) {
                ForwardPort proposed = new ForwardPort();
                proposed.setForwardId(existForward.getId());
                proposed.setNodeId(chainTunnel.getNodeId());
                proposed.setPort(chainTunnel.getPort());
                addedNew.add(proposed);
                GostDto result = GostUtil.AddAndUpdateService(newServiceName, permissionResult.getLimiter(),
                        entryNodes.get(proposed.getNodeId()), existForward, proposed, tunnel, "AddService");
                if (!Objects.equals(result.getMsg(), "OK")) {
                    failure = result.getMsg();
                    break;
                }
            }
        }
        if (failure != null) {
            List<String> rollbackFailures = new ArrayList<>();
            Collections.reverse(addedNew);
            for (ForwardPort added : addedNew) {
                GostDto result = GostUtil.DeleteService(added.getNodeId(),
                        GostUtil.forwardServiceNames(newServiceName, nextProtocol));
                if (!Objects.equals(result.getMsg(), "OK")) rollbackFailures.add(String.valueOf(added.getNodeId()));
            }
            Collections.reverse(deletedOld);
            for (ForwardPort oldPort : deletedOld) {
                Node node = oldNodes.get(oldPort.getNodeId());
                GostDto result = GostUtil.AddAndUpdateService(oldServiceName, oldLimiter,
                        node, previousForward, oldPort, oldTunnel, "AddService");
                if (!Objects.equals(result.getMsg(), "OK")) {
                    result = GostUtil.AddAndUpdateService(oldServiceName, oldLimiter,
                            node, previousForward, oldPort, oldTunnel, "UpdateService");
                }
                if (!Objects.equals(result.getMsg(), "OK")) rollbackFailures.add(String.valueOf(node.getId()));
            }
            return R.err(rollbackFailures.isEmpty() ? failure
                    : failure + "；以下节点回滚失败：" + String.join(",", rollbackFailures));
        }

        this.updateById(existForward);
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", existForward.getId()));
        for (ChainTunnel chainTunnel : chainTunnels) {
            ForwardPort port = new ForwardPort();
            port.setForwardId(existForward.getId());
            port.setNodeId(chainTunnel.getNodeId());
            port.setPort(chainTunnel.getPort());
            forwardPortService.save(port);
        }
        return R.ok();
    }

    @Override
    public R deleteForward(Long id) {

        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();


        // 2. 检查转发是否存在
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }


        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        UserPermissionResult permissionResult = checkUserPermissions(currentUser, tunnel, null);
        if (permissionResult.isHasError()) {
            return R.err(permissionResult.getErrorMessage());
        }
        UserTunnel userTunnel = null;
        if (currentUser.getRoleId() != 0) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        } else {
            // 管理员用户也需要获取UserTunnel（如果存在的话），用于构建正确的服务名称
            // 通过forward记录获取原始的用户ID
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));
        for (ChainTunnel chainTunnel : chainTunnels) {

            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("部分节点不存在");
            }

            JSONArray services = GostUtil.forwardServiceNames(serviceName, forward.getProtocol());
            GostDto gostDto = GostUtil.DeleteService(node.getId(), services);
            if (!Objects.equals(gostDto.getMsg(), "OK")) return R.err(gostDto.getMsg());
        }
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        this.removeById(id);
        jdbc.update("DELETE FROM forward_flow_hour WHERE forward_id=?", id);
        return R.ok();
    }

    @Override
    public synchronized R pauseForward(Long id) {
        return changeForwardStatus(id, 0, "PauseService");
    }

    @Override
    public synchronized R resumeForward(Long id) {
        return changeForwardStatus(id, 1, "ResumeService");
    }

    @Override
    public R forceDeleteForward(Long id) {
        UserInfo currentUser = getCurrentUserInfo();
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("端口转发不存在");
        }
        this.removeById(id);
        jdbc.update("DELETE FROM forward_flow_hour WHERE forward_id=?", id);
        forwardPortService.remove(new QueryWrapper<ForwardPort>().eq("forward_id", id));
        return R.ok();
    }

    @Override
    public R diagnoseForward(Long id) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 检查转发是否存在且用户有权限访问
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        // 3. 获取隧道信息
        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        // 4. 获取隧道的ChainTunnel信息
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId())
        );

        if (chainTunnels.isEmpty()) {
            return R.err("隧道配置不完整");
        }

        // 分类节点
        List<ChainTunnel> inNodes = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 1)
                .toList();

        Map<Integer, List<ChainTunnel>> chainNodesMap = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 2)
                .collect(Collectors.groupingBy(
                        ct -> ct.getInx() != null ? ct.getInx() : 0,
                        Collectors.toList()
                ));

        List<List<ChainTunnel>> chainNodesList = chainNodesMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();

        List<ChainTunnel> outNodes = chainTunnels.stream()
                .filter(ct -> ct.getChainType() == 3)
                .toList();

        List<DiagnosisResult> results = new ArrayList<>();
        String[] remoteAddresses = forward.getRemoteAddr().split(",");

        // 根据隧道类型执行不同的诊断策略
        if (tunnel.getType() == 1) {
            // 端口转发：入口节点直接TCP ping目标地址
            for (ChainTunnel inNode : inNodes) {
                Node node = nodeService.getById(inNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "入口(" + node.getName() + ")->目标(" + remoteAddress + ")"
                            );
                            result.setFromChainType(1);
                            results.add(result);
                        }
                    }
                }
            }
        } else if (tunnel.getType() == 2) {
            // 隧道转发：测试完整链路
            // 1. 入口->第一跳（或出口）
            for (ChainTunnel inNode : inNodes) {
                Node fromNode = nodeService.getById(inNode.getNodeId());

                if (fromNode != null) {
                    if (!chainNodesList.isEmpty()) {
                        for (ChainTunnel firstChainNode : chainNodesList.getFirst()) {
                            Node toNode = nodeService.getById(firstChainNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), firstChainNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->第1跳(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(2);
                                result.setToInx(firstChainNode.getInx());
                                results.add(result);
                            }
                        }
                    } else if (!outNodes.isEmpty()) {
                        for (ChainTunnel outNode : outNodes) {
                            Node toNode = nodeService.getById(outNode.getNodeId());
                            if (toNode != null) {
                                DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                        fromNode, toNode.getServerIp(), outNode.getPort(),
                                        "入口(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                );
                                result.setFromChainType(1);
                                result.setToChainType(3);
                                results.add(result);
                            }
                        }
                    }
                }
            }

            // 2. 链路测试
            for (int i = 0; i < chainNodesList.size(); i++) {
                List<ChainTunnel> currentHop = chainNodesList.get(i);

                for (ChainTunnel currentNode : currentHop) {
                    Node fromNode = nodeService.getById(currentNode.getNodeId());

                    if (fromNode != null) {
                        if (i + 1 < chainNodesList.size()) {
                            for (ChainTunnel nextNode : chainNodesList.get(i + 1)) {
                                Node toNode = nodeService.getById(nextNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), nextNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->第" + (i + 2) + "跳(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(2);
                                    result.setToInx(nextNode.getInx());
                                    results.add(result);
                                }
                            }
                        } else if (!outNodes.isEmpty()) {
                            for (ChainTunnel outNode : outNodes) {
                                Node toNode = nodeService.getById(outNode.getNodeId());
                                if (toNode != null) {
                                    DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                            fromNode, toNode.getServerIp(), outNode.getPort(),
                                            "第" + (i + 1) + "跳(" + fromNode.getName() + ")->出口(" + toNode.getName() + ")"
                                    );
                                    result.setFromChainType(2);
                                    result.setFromInx(currentNode.getInx());
                                    result.setToChainType(3);
                                    results.add(result);
                                }
                            }
                        }
                    }
                }
            }

            // 3. 出口->目标地址
            for (ChainTunnel outNode : outNodes) {
                Node node = nodeService.getById(outNode.getNodeId());
                if (node != null) {
                    for (String remoteAddress : remoteAddresses) {
                        String targetIp = extractIpFromAddress(remoteAddress);
                        int targetPort = extractPortFromAddress(remoteAddress);
                        if (targetIp != null && targetPort != -1) {
                            DiagnosisResult result = performTcpPingDiagnosisWithConnectionCheck(
                                    node, targetIp, targetPort,
                                    "出口(" + node.getName() + ")->目标(" + remoteAddress + ")"
                            );
                            result.setFromChainType(3);
                            results.add(result);
                        }
                    }
                }
            }
        }

        // 构建诊断报告
        Map<String, Object> diagnosisReport = new HashMap<>();
        diagnosisReport.put("forwardId", id);
        diagnosisReport.put("forwardName", forward.getName());
        diagnosisReport.put("tunnelType", tunnel.getType() == 1 ? "端口转发" : "隧道转发");
        diagnosisReport.put("results", results);
        diagnosisReport.put("timestamp", System.currentTimeMillis());

        return R.ok(diagnosisReport);
    }

    @Override
    @Transactional
    public R updateForwardOrder(Map<String, Object> params) {
        // 1. 获取当前用户信息
        UserInfo currentUser = getCurrentUserInfo();

        // 2. 验证参数
        if (!params.containsKey("forwards")) {
            return R.err("缺少forwards参数");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forwardsList = (List<Map<String, Object>>) params.get("forwards");
        if (forwardsList == null || forwardsList.isEmpty()) {
            return R.err("forwards参数不能为空");
        }

        // 3. 验证用户权限（只能更新自己的转发）
        if (currentUser.getRoleId() != 0) {
            // 普通用户只能更新自己的转发
            List<Long> forwardIds = forwardsList.stream()
                    .map(item -> Long.valueOf(item.get("id").toString()))
                    .collect(Collectors.toList());

            // 检查所有转发是否属于当前用户
            QueryWrapper<Forward> queryWrapper = new QueryWrapper<>();
            queryWrapper.in("id", forwardIds);
            queryWrapper.eq("user_id", currentUser.getUserId());

            long count = this.count(queryWrapper);
            if (count != forwardIds.size()) {
                return R.err("只能更新自己的转发排序");
            }
        }

        // 4. 批量更新排序
        List<Forward> forwardsToUpdate = new ArrayList<>();
        for (Map<String, Object> forwardData : forwardsList) {
            Long id = Long.valueOf(forwardData.get("id").toString());
            Integer inx = Integer.valueOf(forwardData.get("inx").toString());

            Forward forward = new Forward();
            forward.setId(id);
            forward.setInx(inx);
            forwardsToUpdate.add(forward);
        }

        // 5. 执行批量更新
        this.updateBatchById(forwardsToUpdate);
        return R.ok();


    }


    private R changeForwardStatus(Long id, int targetStatus, String gostMethod) {
        UserInfo currentUser = getCurrentUserInfo();
        if (currentUser.getRoleId() != 0) {
            User user = userService.getById(currentUser.getUserId());
            if (user == null) return R.err("用户不存在");
            if (user.getStatus() == 0) return R.err("用户已到期或被禁用");
        }
        Forward forward = validateForwardExists(id, currentUser);
        if (forward == null) {
            return R.err("转发不存在");
        }

        Tunnel tunnel = validateTunnel(forward.getTunnelId());
        if (tunnel == null) {
            return R.err("隧道不存在");
        }

        UserTunnel userTunnel = null;
        if (targetStatus == 1) {
            if (tunnel.getStatus() != 1) {
                return R.err("隧道已禁用，无法恢复服务");
            }
            if (currentUser.getRoleId() != 0) {
                R flowCheckResult = checkUserFlowLimits(currentUser.getUserId(), tunnel);
                if (flowCheckResult.getCode() != 0) {
                    return flowCheckResult;
                }
                userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
                if (userTunnel == null) {
                    return R.err("你没有该隧道权限");
                }
                if (userTunnel.getStatus() != 1) {
                    return R.err("隧道被禁用");
                }
            }
        }
        if (currentUser.getRoleId() != 0 && userTunnel == null) {
            userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
            if (userTunnel == null) {
                return R.err("你没有该隧道权限");
            }
        }

        if (userTunnel == null) {
            userTunnel = getUserTunnel(forward.getUserId(), tunnel.getId().intValue());
        }

        List<ChainTunnel> chainTunnels = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("tunnel_id", tunnel.getId()).eq("chain_type", 1));

        for (ChainTunnel chainTunnel : chainTunnels) {
            String serviceName = buildServiceName(forward.getId(), forward.getUserId(), userTunnel);
            Node node = nodeService.getById(chainTunnel.getNodeId());
            if (node == null) {
                return R.err("部分节点不存在");
            }
            GostDto gostDto = GostUtil.PauseAndResumeService(node.getId(), serviceName, forward.getProtocol(), gostMethod);
            if (!Objects.equals(gostDto.getMsg(), "OK")) return R.err(gostDto.getMsg());
        }
        forward.setStatus(targetStatus);
        forward.setUpdatedTime(System.currentTimeMillis());
        this.updateById(forward);
        return R.ok();
    }

    private String extractIpFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1) {
                return address.substring(1, closeBracket);
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0) {
            return address.substring(0, lastColon);
        }

        // 如果没有端口，直接返回地址
        return address;
    }

    private int extractPortFromAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return -1;
        }

        address = address.trim();

        // IPv6格式: [ipv6]:port
        if (address.startsWith("[")) {
            int closeBracket = address.indexOf(']');
            if (closeBracket > 1 && closeBracket + 1 < address.length() && address.charAt(closeBracket + 1) == ':') {
                String portStr = address.substring(closeBracket + 2);
                try {
                    return Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }

        // IPv4或域名格式: ip:port 或 domain:port
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon + 1 < address.length()) {
            String portStr = address.substring(lastColon + 1);
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        // 如果没有端口，返回-1表示无法解析
        return -1;
    }

    private DiagnosisResult performTcpPingDiagnosis(Node node, String targetIp, int port, String description) {
        try {
            // 构建TCP ping请求数据
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", targetIp);
            tcpPingData.put("port", port);
            tcpPingData.put("count", 4);
            tcpPingData.put("timeout", 5000); // 5秒超时

            // 发送TCP ping命令到节点
            GostDto gostResult = WebSocketServer.send_msg(node.getId(), tcpPingData, "TcpPing");

            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setTimestamp(System.currentTimeMillis());

            if (gostResult != null && "OK".equals(gostResult.getMsg())) {
                // 尝试解析TCP ping响应数据
                try {
                    if (gostResult.getData() != null) {
                        JSONObject tcpPingResponse = (JSONObject) gostResult.getData();
                        boolean success = tcpPingResponse.getBooleanValue("success");

                        result.setSuccess(success);
                        if (success) {
                            result.setMessage("TCP连接成功");
                            result.setAverageTime(tcpPingResponse.getDoubleValue("averageTime"));
                            result.setPacketLoss(tcpPingResponse.getDoubleValue("packetLoss"));
                        } else {
                            result.setMessage(tcpPingResponse.getString("errorMessage"));
                            result.setAverageTime(-1.0);
                            result.setPacketLoss(100.0);
                        }
                    } else {
                        // 没有详细数据，使用默认值
                        result.setSuccess(true);
                        result.setMessage("TCP连接成功");
                        result.setAverageTime(0.0);
                        result.setPacketLoss(0.0);
                    }
                } catch (Exception e) {
                    // 解析响应数据失败，但TCP ping命令本身成功了
                    result.setSuccess(true);
                    result.setMessage("TCP连接成功，但无法解析详细数据");
                    result.setAverageTime(0.0);
                    result.setPacketLoss(0.0);
                }
            } else {
                result.setSuccess(false);
                result.setMessage(gostResult != null ? gostResult.getMsg() : "节点无响应");
                result.setAverageTime(-1.0);
                result.setPacketLoss(100.0);
            }

            return result;
        } catch (Exception e) {
            DiagnosisResult result = new DiagnosisResult();
            result.setNodeId(node.getId());
            result.setNodeName(node.getName());
            result.setTargetIp(targetIp);
            result.setTargetPort(port);
            result.setDescription(description);
            result.setSuccess(false);
            result.setMessage("诊断执行异常: " + e.getMessage());
            result.setTimestamp(System.currentTimeMillis());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private DiagnosisResult performTcpPingDiagnosisWithConnectionCheck(Node node, String targetIp, int port, String description) {
        DiagnosisResult result = new DiagnosisResult();
        result.setNodeId(node.getId());
        result.setNodeName(node.getName());
        result.setTargetIp(targetIp);
        result.setTargetPort(port);
        result.setDescription(description);
        result.setTimestamp(System.currentTimeMillis());

        try {
            return performTcpPingDiagnosis(node, targetIp, port, description);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("连接检查异常: " + e.getMessage());
            result.setAverageTime(-1.0);
            result.setPacketLoss(100.0);
            return result;
        }
    }

    private UserInfo getCurrentUserInfo() {
        Integer userId = JwtUtil.getUserIdFromToken();
        Integer roleId = JwtUtil.getRoleIdFromToken();
        String userName = JwtUtil.getNameFromToken();
        return new UserInfo(userId, roleId, userName);
    }

    private boolean validGroup(Integer groupId, Integer ownerId) {
        if (groupId == null || groupId == 0) return true;
        if (groupId < 0) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM forward_group WHERE id=? AND user_id=?",
                Integer.class, groupId, ownerId);
        return count != null && count == 1;
    }

    private Tunnel validateTunnel(Integer tunnelId) {
        return tunnelService.getById(tunnelId);
    }

    private Forward validateForwardExists(Long forwardId, UserInfo currentUser) {
        Forward forward = this.getById(forwardId);
        if (forward == null) {
            return null;
        }

        // 普通用户只能操作自己的转发
        if (currentUser.getRoleId() != 0 &&
                !Objects.equals(currentUser.getUserId(), forward.getUserId())) {
            return null;
        }

        return forward;
    }

    private UserPermissionResult checkUserPermissions(UserInfo currentUser, Tunnel tunnel, Long excludeForwardId) {
        if (currentUser.getRoleId() == 0) {
            return UserPermissionResult.success(null, null);
        }

        // 获取用户信息
        User userInfo = userService.getById(currentUser.getUserId());
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("当前账号已到期");
        }

        // 检查用户隧道权限
        UserTunnel userTunnel = getUserTunnel(currentUser.getUserId(), tunnel.getId().intValue());
        if (userTunnel == null) {
            return UserPermissionResult.error("你没有该隧道权限");
        }

        if (userTunnel.getStatus() != 1) {
            return UserPermissionResult.error("隧道被禁用");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return UserPermissionResult.error("该隧道权限已到期");
        }

        // 流量限制检查
        if (userInfo.getFlow() <= 0) {
            return UserPermissionResult.error("用户总流量已用完");
        }
        if (userTunnel.getFlow() <= 0) {
            return UserPermissionResult.error("该隧道流量已用完");
        }

        // 转发数量限制检查
        R quotaCheckResult = checkForwardQuota(currentUser.getUserId(), tunnel.getId().intValue(), userTunnel, userInfo, excludeForwardId);
        if (quotaCheckResult.getCode() != 0) {
            return UserPermissionResult.error(quotaCheckResult.getMsg());
        }

        return UserPermissionResult.success(userTunnel.getSpeedId(), userTunnel);
    }

    private R checkForwardQuota(Integer userId, Integer tunnelId, UserTunnel userTunnel, User userInfo, Long excludeForwardId) {
        // 检查用户总转发数量限制
        QueryWrapper<Forward> userQuery = new QueryWrapper<Forward>().eq("user_id", userId);
        if (excludeForwardId != null) userQuery.ne("id", excludeForwardId);
        long userForwardCount = this.count(userQuery);
        if (userForwardCount >= userInfo.getNum()) {
            return R.err("用户总转发数量已达上限，当前限制：" + userInfo.getNum() + "个");
        }

        // 检查用户在该隧道的转发数量限制
        QueryWrapper<Forward> tunnelQuery = new QueryWrapper<Forward>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId);

        if (excludeForwardId != null) {
            tunnelQuery.ne("id", excludeForwardId);
        }

        long tunnelForwardCount = this.count(tunnelQuery);
        if (tunnelForwardCount >= userTunnel.getNum()) {
            return R.err("该隧道转发数量已达上限，当前限制：" + userTunnel.getNum() + "个");
        }

        return R.ok();
    }

    private R checkUserFlowLimits(Integer userId, Tunnel tunnel) {
        User userInfo = userService.getById(userId);
        if (userInfo.getExpTime() != null && userInfo.getExpTime() <= System.currentTimeMillis()) {
            return R.err("当前账号已到期");
        }

        UserTunnel userTunnel = getUserTunnel(userId, tunnel.getId().intValue());
        if (userTunnel == null) {
            return R.err("你没有该隧道权限");
        }

        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            return R.err("该隧道权限已到期，无法恢复服务");
        }

        // 检查用户总流量限制
        if (userInfo.getFlow() * BYTES_TO_GB <= userInfo.getInFlow() + userInfo.getOutFlow()) {
            return R.err("用户总流量已用完，无法恢复服务");
        }

        // 检查隧道流量限制
        // 数据库中的流量已按计费类型处理，直接使用总和
        long tunnelFlow = userTunnel.getInFlow() + userTunnel.getOutFlow();

        if (userTunnel.getFlow() * BYTES_TO_GB <= tunnelFlow) {
            return R.err("该隧道流量已用完，无法恢复服务");
        }

        return R.ok();
    }

    private UserTunnel getUserTunnel(Integer userId, Integer tunnelId) {
        return userTunnelService.getOne(new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId));
    }

    private String buildServiceName(Long forwardId, Integer userId, UserTunnel userTunnel) {
        int userTunnelId = (userTunnel != null) ? userTunnel.getId() : 0;
        return forwardId + "_" + userId + "_" + userTunnelId;
    }


    private List<ChainTunnel> allocateForwardPorts(Forward rule, List<ChainTunnel> entries, Integer requested, Long excluding) {
        if (rule.getTlsInboundId() == null || rule.getTlsInboundId() == 0) {
            rule.setSniNames("");
            return get_port(entries, requested, excluding);
        }
        if (!"tcp".equals(rule.getProtocol()) || Integer.valueOf(1).equals(rule.getOriginTls())
                || (rule.getProxyAccept() != null && rule.getProxyAccept() != 0)) {
            throw new IllegalArgumentException("共享 TLS 仅支持 TCP，不可同时使用回源 TLS 或接收 Proxy");
        }
        var found = jdbc.queryForList("SELECT tunnel_id,port,allowed_sni FROM forward_tls_inbound WHERE id=?", rule.getTlsInboundId());
        if (found.size() != 1 || ((Number) found.getFirst().get("tunnel_id")).intValue() != rule.getTunnelId()) {
            throw new IllegalArgumentException("共享 TLS 入口不属于所选隧道");
        }
        String names = ForwardSni.normalize(rule.getSniNames(), false);
        if (names.isEmpty()) throw new IllegalArgumentException("请填写至少一个 SNI 域名");
        String allowed = (String) found.getFirst().get("allowed_sni");
        List<String> occupied = jdbc.queryForList("SELECT sni_names FROM forward WHERE tls_inbound_id=? AND id<>?",
                String.class, rule.getTlsInboundId(), excluding);
        Set<String> taken = new HashSet<>();
        for (String existing : occupied) taken.addAll(Arrays.asList(existing.split(",")));
        for (String name : names.split(",")) {
            if (!ForwardSni.allows(allowed, name)) throw new IllegalArgumentException("SNI 超出入口允许范围：" + name);
            if (taken.contains(name)) throw new IllegalArgumentException("SNI 已被其他规则使用：" + name);
        }
        int port = ((Number) found.getFirst().get("port")).intValue();
        for (ChainTunnel entry : entries) {
            Integer conflicts = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM chain_tunnel WHERE node_id=? AND port=?) + " +
                    "(SELECT COUNT(*) FROM forward_port p JOIN forward f ON f.id=p.forward_id " +
                    "WHERE p.node_id=? AND p.port=? AND f.id<>? AND COALESCE(f.tls_inbound_id,0)<>?)",
                    Integer.class, entry.getNodeId(), port, entry.getNodeId(), port, excluding, rule.getTlsInboundId());
            if (conflicts != null && conflicts > 0) throw new IllegalArgumentException("共享 TLS 端口已被占用");
            entry.setPort(port);
        }
        rule.setSniNames(names);
        return entries;
    }

    public List<ChainTunnel> get_port(List<ChainTunnel> chainTunnelList, Integer in_port, Long forward_id) {
        List<List<Integer>> list = new ArrayList<>();

        // 获取每个节点的端口列表
        for (ChainTunnel tunnel : chainTunnelList) {
            List<Integer> nodePort = getNodePort(tunnel.getNodeId(), forward_id);
            if (nodePort.isEmpty()) {
                throw new RuntimeException("暂无可用端口");
            }
            list.add(nodePort);
        }

        // ========== 如果指定了 in_port，优先检查公有 ==========
        if (in_port != null) {
            for (List<Integer> ports : list) {
                if (!ports.contains(in_port)) {
                    throw new RuntimeException("指定端口 " + in_port + " 不可用（并非所有节点都有此端口）");
                }
            }

            // 所有节点都有该端口 设置回 ChainTunnel
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(in_port);
            }
            return chainTunnelList;
        }

        // ========== 未指定 in_port 查找最小的共同端口 ==========
        Set<Integer> intersection = new HashSet<>(list.getFirst());
        for (int i = 1; i < list.size(); i++) {
            intersection.retainAll(list.get(i));
        }

        if (!intersection.isEmpty()) {
            // 找最小端口
            Integer commonMin = intersection.stream().min(Integer::compareTo).orElseThrow();

            // 设置到所有节点
            for (ChainTunnel tunnel : chainTunnelList) {
                tunnel.setPort(commonMin);
            }

            return chainTunnelList;
        }

        // ========== 没有共同端口取各自第一个可用端口 ==========
        for (int i = 0; i < chainTunnelList.size(); i++) {
            List<Integer> ports = list.get(i);
            Integer first = ports.getFirst();
            chainTunnelList.get(i).setPort(first);
        }

        return chainTunnelList;
    }

    public List<Integer> getNodePort(Long nodeId, Long forward_id) {

        Node node = nodeService.getById(nodeId);
        if (node == null) {
            throw new RuntimeException("节点不存在");
        }

        // 1. 查询隧道转发链占用的端口
        List<ChainTunnel> chainTunnels = chainTunnelService.list(
                new QueryWrapper<ChainTunnel>().eq("node_id", nodeId)
        );
        Set<Integer> usedPorts = chainTunnels.stream()
                .map(ChainTunnel::getPort)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());


        List<ForwardPort> list = forwardPortService.list(new QueryWrapper<ForwardPort>().eq("node_id", nodeId).ne("forward_id", forward_id));
        Set<Integer> forwardUsedPorts = new HashSet<>();
        for (ForwardPort forwardPort : list) {
            forwardUsedPorts.add(forwardPort.getPort());
        }
        usedPorts.addAll(forwardUsedPorts);
        usedPorts.addAll(jdbc.queryForList("SELECT i.port FROM forward_tls_inbound i JOIN chain_tunnel c " +
                "ON c.tunnel_id=i.tunnel_id WHERE c.chain_type=1 AND c.node_id=?", Integer.class, nodeId));

        List<Integer> parsedPorts = TunnelServiceImpl.parsePorts(node.getPort());
        return parsedPorts.stream()
                .filter(p -> !usedPorts.contains(p))
                .toList();
    }



    // ========== 内部数据类 ==========

    @Data
    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;
        private final String userName;

        public UserInfo(Integer userId, Integer roleId, String userName) {
            this.userId = userId;
            this.roleId = roleId;
            this.userName = userName;
        }
    }

    @Data
    private static class UserPermissionResult {
        private final boolean hasError;
        private final String errorMessage;
        private final Integer limiter;
        private final UserTunnel userTunnel;

        private UserPermissionResult(boolean hasError, String errorMessage, Integer limiter, UserTunnel userTunnel) {
            this.hasError = hasError;
            this.errorMessage = errorMessage;
            this.limiter = limiter;
            this.userTunnel = userTunnel;
        }

        public static UserPermissionResult success(Integer limiter, UserTunnel userTunnel) {
            return new UserPermissionResult(false, null, limiter, userTunnel);
        }

        public static UserPermissionResult error(String errorMessage) {
            return new UserPermissionResult(true, errorMessage, null, null);
        }
    }

    @Data
    public static class DiagnosisResult {
        private Long nodeId;
        private String nodeName;
        private String targetIp;
        private Integer targetPort;
        private String description;
        private boolean success;
        private String message;
        private double averageTime;
        private double packetLoss;
        private long timestamp;

        // 链路类型相关字段
        private Integer fromChainType; // 1: 入口, 2: 链, 3: 出口
        private Integer fromInx;
        private Integer toChainType;
        private Integer toInx;
    }

}
