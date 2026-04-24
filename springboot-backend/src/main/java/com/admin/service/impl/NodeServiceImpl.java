package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.GostDto;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.*;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;

@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {


    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    ChainTunnelService chainTunnelService;

    @Resource
    UserNodePermissionService userNodePermissionService;

    @Override
    public R createNode(NodeDto nodeDto) {
        validatePortRange(nodeDto.getPort());
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        Node node = new Node();
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(0);
        node.setPort(nodeDto.getPort());
        node.setName(nodeDto.getName());
        node.setServerIp(nodeDto.getServerIp());
        node.setOwnerUserId(roleId == 0 ? null : userId);
        node.setCreatedByRole(roleId);
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);
        node.setInterfaceName(nodeDto.getInterfaceName());
        this.save(node);
        if (roleId != 0) {
            userNodePermissionService.grantNodeOwnerPermissions(userId, node.getId());
        }
        return R.ok();
    }

    @Override
    public R getAllNodes() {
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        List<Node> nodeList;
        Map<Integer, UserNodePermission> permissionMap = java.util.Collections.emptyMap();

        if (roleId == 0) {
            nodeList = this.list(new QueryWrapper<Node>().orderByDesc("status"));
        } else {
            permissionMap = userNodePermissionService.getUserNodePermissionMap(userId);
            Set<Integer> permittedIds = permissionMap.keySet();
            QueryWrapper<Node> queryWrapper = new QueryWrapper<>();
            if (permittedIds.isEmpty()) {
                queryWrapper.eq("owner_user_id", userId);
            } else {
                queryWrapper.and(w -> w.eq("owner_user_id", userId).or().in("id", permittedIds));
            }
            queryWrapper.orderByDesc("status");
            nodeList = this.list(queryWrapper);
        }

        Map<Integer, UserNodePermission> finalPermissionMap = permissionMap;
        Integer finalRoleId = roleId;
        Integer finalUserId = userId;
        nodeList.forEach(node -> {
            node.setSecret(null);
            boolean owner = finalUserId != null && Objects.equals(node.getOwnerUserId(), finalUserId);
            node.setManageable(finalRoleId == 0 || owner);
            if (finalRoleId == 0 || owner) {
                node.setAllowIn(true);
                node.setAllowOut(true);
            } else {
                UserNodePermission permission = finalPermissionMap.get(node.getId().intValue());
                node.setAllowIn(permission != null && permission.getAllowIn() == 1);
                node.setAllowOut(permission != null && permission.getAllowOut() == 1);
            }
        });
        return R.ok(nodeList);
    }

    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        Node node = this.getById(nodeUpdateDto.getId());
        if (node == null) {
            return R.err("节点不存在");
        }
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        if (roleId != 0 && !Objects.equals(node.getOwnerUserId(), userId)) {
            return R.err("仅可编辑自己创建的节点");
        }

        boolean online = node.getStatus() != null && node.getStatus() == 1;
        Integer newHttp = nodeUpdateDto.getHttp();
        Integer newTls = nodeUpdateDto.getTls();
        Integer newSocks = nodeUpdateDto.getSocks();

        boolean httpChanged = newHttp != null && !newHttp.equals(node.getHttp());
        boolean tlsChanged = newTls != null && !newTls.equals(node.getTls());
        boolean socksChanged = newSocks != null && !newSocks.equals(node.getSocks());

        if (online && (httpChanged || tlsChanged || socksChanged)) {
            JSONObject req = new JSONObject();
            req.put("http", newHttp);
            req.put("tls", newTls);
            req.put("socks", newSocks);

            GostDto gostResult = WebSocketServer.send_msg(node.getId(), req, "SetProtocol");
            if (!Objects.equals(gostResult.getMsg(), "OK")){
                return R.err(gostResult.getMsg());
            }
        }



        Node updateNode = buildUpdateNode(nodeUpdateDto);
        this.updateById(updateNode);
        return R.ok();
    }

    @Override
    public R deleteNode(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            return R.err("节点不存在");
        }
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        if (roleId != 0 && !Objects.equals(node.getOwnerUserId(), userId)) {
            return R.err("仅可删除自己创建的节点");
        }

        List<ChainTunnel> list = chainTunnelService.list(new QueryWrapper<ChainTunnel>().eq("node_id", id).groupBy("tunnel_id"));
        for (ChainTunnel tunnel : list) {
            tunnelService.deleteTunnel(tunnel.getTunnelId());
        }
        userNodePermissionService.remove(new QueryWrapper<UserNodePermission>().eq("node_id", id.intValue()));
        this.removeById(id);
        return R.ok();
    }


    @Override
    public R getInstallCommand(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            return R.err("节点不存在");
        }
        Integer roleId = JwtUtil.getRoleIdFromToken();
        Integer userId = JwtUtil.getUserIdFromToken();
        if (roleId != 0 && !Objects.equals(node.getOwnerUserId(), userId)) {
            return R.err("仅可查看自己创建节点的安装命令");
        }
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (viteConfig == null) return R.err("请先前往网站配置中设置ip");
        StringBuilder command = new StringBuilder();
        command.append("curl -L https://raw.githubusercontent.com/pkhosn/flux-panel/refs/heads/beta/install.sh")
                .append(" -o ./install.sh && chmod +x ./install.sh && ");
        String processedServerAddr = GostUtil.processServerAddress(viteConfig.getValue());
        command.append("./install.sh")
                .append(" -a ").append(processedServerAddr)  // 服务器地址
                .append(" -s ").append(node.getSecret());    // 节点密钥
        return R.ok(command);

    }


    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        validatePortRange(nodeUpdateDto.getPort());
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setPort(nodeUpdateDto.getPort());
        node.setHttp(nodeUpdateDto.getHttp());
        node.setTls(nodeUpdateDto.getTls());
        node.setSocks(nodeUpdateDto.getSocks());
        node.setUpdatedTime(System.currentTimeMillis());
        node.setInterfaceName(nodeUpdateDto.getInterfaceName());
        node.setTcpListenAddr(nodeUpdateDto.getTcpListenAddr());
        node.setUdpListenAddr(nodeUpdateDto.getUdpListenAddr());
        return node;
    }


    private void validatePortRange(String port) {
        Pattern PORT_PATTERN = Pattern.compile(   "([0-9]{1,5})(-([0-9]{1,5}))?");
        if (port == null || port.isEmpty()) {
            throw new RuntimeException("可用端口不合法");
        }
        String[] parts = port.split(",");
        for (String part : parts) {
            part = part.trim();
            if (!PORT_PATTERN.matcher(part).matches()) {
                throw new RuntimeException("可用端口不合法");
            }
            if (part.contains("-")) {
                String[] range = part.split("-");
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                if (start < 0 || end < 0 || end > 65535 || start > end) {
                    throw new RuntimeException("可用端口不合法");
                }
            } else {
                int ports = Integer.parseInt(part);
                if (ports < 0 || ports > 65535) {
                    throw new RuntimeException("可用端口不合法");
                }
            }
        }
    }




}
