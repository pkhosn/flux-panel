package com.admin.service;

import com.admin.common.dto.UserNodePermissionDto;
import com.admin.common.dto.UserNodePermissionQueryDto;
import com.admin.common.dto.UserNodePermissionUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.UserNodePermission;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

public interface UserNodePermissionService extends IService<UserNodePermission> {

    R assignUserNodePermission(UserNodePermissionDto dto);

    R getUserNodePermissionList(UserNodePermissionQueryDto dto);

    R updateUserNodePermission(UserNodePermissionUpdateDto dto);

    R removeUserNodePermission(Long id);

    Map<Integer, UserNodePermission> getUserNodePermissionMap(Integer userId);

    void grantNodeOwnerPermissions(Integer userId, Long nodeId);
}
