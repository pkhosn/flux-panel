package com.admin.service.impl;

import com.admin.common.dto.UserNodePermissionDto;
import com.admin.common.dto.UserNodePermissionQueryDto;
import com.admin.common.dto.UserNodePermissionUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.UserNodePermission;
import com.admin.entity.User;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.UserNodePermissionMapper;
import com.admin.service.UserNodePermissionService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserNodePermissionServiceImpl extends ServiceImpl<UserNodePermissionMapper, UserNodePermission> implements UserNodePermissionService {

    @Autowired
    private NodeMapper nodeMapper;

    @Autowired
    private UserService userService;

    @Override
    public R assignUserNodePermission(UserNodePermissionDto dto) {
        User user = userService.getById(dto.getUserId());
        if (user == null) {
            return R.err("用户不存在");
        }
        Node node = nodeMapper.selectById(dto.getNodeId());
        if (node == null) {
            return R.err("节点不存在");
        }
        if (dto.getAllowIn() != 0 && dto.getAllowIn() != 1) {
            return R.err("入口权限参数非法");
        }
        if (dto.getAllowOut() != 0 && dto.getAllowOut() != 1) {
            return R.err("出口权限参数非法");
        }

        UserNodePermission exist = getOne(new QueryWrapper<UserNodePermission>()
                .eq("user_id", dto.getUserId())
                .eq("node_id", dto.getNodeId())
                .last("limit 1"));

        if (exist != null) {
            exist.setAllowIn(dto.getAllowIn());
            exist.setAllowOut(dto.getAllowOut());
            exist.setUpdatedTime(System.currentTimeMillis());
            updateById(exist);
            return R.ok("更新成功");
        }

        UserNodePermission record = new UserNodePermission();
        record.setUserId(dto.getUserId());
        record.setNodeId(dto.getNodeId());
        record.setAllowIn(dto.getAllowIn());
        record.setAllowOut(dto.getAllowOut());
        record.setStatus(1);
        long now = System.currentTimeMillis();
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        save(record);
        return R.ok("分配成功");
    }

    @Override
    public R getUserNodePermissionList(UserNodePermissionQueryDto dto) {
        List<UserNodePermission> list = list(new QueryWrapper<UserNodePermission>()
                .eq("user_id", dto.getUserId())
                .eq("status", 1));
        if (list.isEmpty()) {
            return R.ok(list);
        }

        List<Long> nodeIds = list.stream().map(item -> Long.valueOf(item.getNodeId())).collect(Collectors.toList());
        Map<Long, String> nodeNameMap = nodeMapper.selectBatchIds(nodeIds).stream()
                .collect(Collectors.toMap(Node::getId, Node::getName, (a, b) -> a));

        List<Map<String, Object>> result = list.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("id", String.valueOf(item.getId()));
            row.put("userId", item.getUserId());
            row.put("nodeId", item.getNodeId());
            row.put("nodeName", nodeNameMap.getOrDefault(Long.valueOf(item.getNodeId()), "未知节点"));
            row.put("allowIn", item.getAllowIn());
            row.put("allowOut", item.getAllowOut());
            row.put("createdTime", item.getCreatedTime());
            row.put("updatedTime", item.getUpdatedTime());
            return row;
        }).collect(Collectors.toList());

        return R.ok(result);
    }

    @Override
    public R updateUserNodePermission(UserNodePermissionUpdateDto dto) {
        UserNodePermission permission = getById(dto.getId());
        if (permission == null) {
            return R.err("权限记录不存在");
        }
        if (dto.getAllowIn() != 0 && dto.getAllowIn() != 1) {
            return R.err("入口权限参数非法");
        }
        if (dto.getAllowOut() != 0 && dto.getAllowOut() != 1) {
            return R.err("出口权限参数非法");
        }

        permission.setAllowIn(dto.getAllowIn());
        permission.setAllowOut(dto.getAllowOut());
        permission.setUpdatedTime(System.currentTimeMillis());
        updateById(permission);
        return R.ok("更新成功");
    }

    @Override
    public R removeUserNodePermission(Long id) {
        UserNodePermission permission = getById(id);
        if (permission == null) {
            return R.err("权限记录不存在");
        }
        removeById(id);
        return R.ok("删除成功");
    }

    @Override
    public Map<Integer, UserNodePermission> getUserNodePermissionMap(Integer userId) {
        return list(new QueryWrapper<UserNodePermission>().eq("user_id", userId).eq("status", 1))
                .stream()
                .collect(Collectors.toMap(UserNodePermission::getNodeId, item -> item, (a, b) -> a));
    }

    @Override
    public void grantNodeOwnerPermissions(Integer userId, Long nodeId) {
        UserNodePermissionDto dto = new UserNodePermissionDto();
        dto.setUserId(userId);
        dto.setNodeId(nodeId.intValue());
        dto.setAllowIn(1);
        dto.setAllowOut(1);
        assignUserNodePermission(dto);
    }
}
