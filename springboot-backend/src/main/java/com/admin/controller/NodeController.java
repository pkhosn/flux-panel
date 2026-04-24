package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.dto.UserNodePermissionDto;
import com.admin.common.dto.UserNodePermissionQueryDto;
import com.admin.common.dto.UserNodePermissionUpdateDto;
import com.admin.common.lang.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node")
public class NodeController extends BaseController {

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody NodeDto nodeDto) {
        return nodeService.createNode(nodeDto);
    }


    @LogAnnotation
    @PostMapping("/list")
    public R list() {
        return nodeService.getAllNodes();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody NodeUpdateDto nodeUpdateDto) {
        return nodeService.updateNode(nodeUpdateDto);
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return nodeService.deleteNode(id);
    }

    @LogAnnotation
    @PostMapping("/install")
    public R getInstallCommand(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return nodeService.getInstallCommand(id);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/assign")
    public R assignUserNodePermission(@Validated @RequestBody UserNodePermissionDto dto) {
        return userNodePermissionService.assignUserNodePermission(dto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/list")
    public R getUserNodePermissionList(@Validated @RequestBody UserNodePermissionQueryDto dto) {
        return userNodePermissionService.getUserNodePermissionList(dto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/update")
    public R updateUserNodePermission(@Validated @RequestBody UserNodePermissionUpdateDto dto) {
        return userNodePermissionService.updateUserNodePermission(dto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/user/remove")
    public R removeUserNodePermission(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return userNodePermissionService.removeUserNodePermission(id);
    }

}
