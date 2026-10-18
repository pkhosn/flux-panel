package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/node/group")
public class NodeGroupController {
    private final JdbcTemplate jdbc;
    public NodeGroupController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private boolean admin() { return JwtUtil.getRoleIdFromToken() == 0; }
    private int owner() { return admin() ? 0 : JwtUtil.getUserIdFromToken(); }
    private String scope() { return admin() ? "1=1" : "owner_user_id=" + JwtUtil.getUserIdFromToken(); }
    private String name(Map<String,Object> input) {
        Object raw=input.get("name");
        return raw instanceof String value && value.trim().length()<=64 ? value.trim() : null;
    }
    @PostMapping("/list")
    public R list() {
        jdbc.update("INSERT OR IGNORE INTO node_group(user_id,name) SELECT ?,group_name FROM node WHERE "+scope()+" AND group_name<>''",owner());
        return R.ok(jdbc.queryForList("SELECT g.name,(SELECT COUNT(*) FROM node WHERE group_name=g.name AND "+scope()+") AS nodeCount FROM node_group g WHERE g.user_id=? ORDER BY g.name",owner()));
    }
    @PostMapping("/create")
    public R create(@RequestBody Map<String,Object> input) {
        String name=name(input);
        if(name==null||name.isEmpty()) return R.err("分组名称须为 1–64 个字符");
        int changed=jdbc.update("INSERT OR IGNORE INTO node_group(user_id,name) VALUES(?,?)",owner(),name);
        return changed==1 ? R.ok() : R.err("分组名称已存在");
    }
    @PostMapping("/delete")
    @Transactional
    public R delete(@RequestBody Map<String,Object> input) {
        String name=name(input);
        if(name==null||name.isEmpty()) return R.err("分组名称无效");
        int changed=jdbc.update("DELETE FROM node_group WHERE user_id=? AND name=?",owner(),name);
        if(changed!=1) return R.err("分组不存在或无权操作");
        jdbc.update("UPDATE node SET group_name='' WHERE group_name=? AND "+scope(),name);
        return R.ok();
    }
    @PostMapping("/assign")
    @Transactional
    public R assign(@RequestBody Map<String,Object> input) {
        String name=name(input);
        if(name==null) return R.err("分组名称无效");
        if(!name.isEmpty() && jdbc.queryForObject("SELECT COUNT(*) FROM node_group WHERE user_id=? AND name=?",Integer.class,owner(),name)!=1) return R.err("分组不存在");
        if(!(input.get("ids") instanceof List<?> values)||values.isEmpty()||values.size()>500) return R.err("请选择 1–500 个节点");
        Set<Long> ids=new LinkedHashSet<>();
        try { for(Object value:values) {long id=Long.parseLong(String.valueOf(value));if(id<=0||!ids.add(id)) return R.err("节点 ID 无效或重复");} }
        catch(NumberFormatException ex){return R.err("节点 ID 无效");}
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        String condition="id IN ("+placeholders+") AND "+scope();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE "+condition,Integer.class,ids.toArray())!=ids.size()) return R.err("节点不存在或无管理权限");
        List<Object> args=new ArrayList<>();args.add(name);args.addAll(ids);
        jdbc.update("UPDATE node SET group_name=? WHERE "+condition,args.toArray());
        return R.ok();
    }
}
