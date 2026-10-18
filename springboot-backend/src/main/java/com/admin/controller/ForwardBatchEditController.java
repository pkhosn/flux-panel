package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.utils.JwtUtil;
import com.admin.service.ForwardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/forward")
public class ForwardBatchEditController {
    private final JdbcTemplate jdbc;
    private final ForwardService service;
    public ForwardBatchEditController(JdbcTemplate jdbc, ForwardService service) {this.jdbc=jdbc;this.service=service;}
    @PostMapping("/batch-edit")
    public R edit(@RequestBody Map<String,Object> input) {
        if (!(input.get("ids") instanceof List<?> ids) || ids.isEmpty() || ids.size()>500 || !(input.get("text") instanceof String text) || text.length()>1024*1024) return R.err("请选择 1–500 条规则并填写内容");
        String[] lines=text.strip().split("\\r?\\n",-1);
        if(lines.length!=ids.size())return R.err("行数必须与所选规则数量一致，不能增删或调整行序");
        List<ForwardUpdateDto> updates=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(int i=0;i<lines.length;i++) {
            long id;
            try{id=Long.parseLong(String.valueOf(ids.get(i)));}catch(NumberFormatException e){return R.err("规则 ID 无效");}
            if(id<=0||!seen.add(id))return R.err("规则 ID 无效或重复");
            String[] fields=lines[i].split("\\|",-1);
            if(fields.length!=3||fields[1].trim().isEmpty()||fields[1].trim().length()>200)return R.err("第 "+(i+1)+" 行格式无效，应为 目标地址|名称|入口端口");
            int port;
            try{port=Integer.parseInt(fields[2].trim());}catch(NumberFormatException e){return R.err("第 "+(i+1)+" 行入口端口无效");}
            if(port<1||port>65535)return R.err("第 "+(i+1)+" 行入口端口应为 1–65535");
            String remote=fields[0].trim();
            if(!validRemote(remote))return R.err("第 "+(i+1)+" 行目标地址无效");
            var rows=jdbc.queryForList("SELECT * FROM forward WHERE id=?",id);
            if(rows.size()!=1)return R.err("规则不存在或无权修改");
            var row=rows.getFirst();
            int owner=((Number)row.get("user_id")).intValue();
            if(JwtUtil.getRoleIdFromToken()!=0&&owner!=JwtUtil.getUserIdFromToken())return R.err("规则不存在或无权修改");
            var dto=new ForwardUpdateDto();dto.setId(id);dto.setUserId(owner);dto.setName(fields[1].trim());dto.setRemoteAddr(remote);dto.setInPort(port);
            // Null optional fields preserve the existing advanced configuration in updateForward.
            dto.setTunnelId(((Number)row.get("tunnel_id")).intValue());dto.setStrategy((String)row.get("strategy"));
            updates.add(dto);
        }
        List<Map<String,Object>> failures=new ArrayList<>();List<Long> succeeded=new ArrayList<>();
        for(int i=0;i<updates.size();i++) {
            var dto=updates.get(i);String error=null;
            var original=jdbc.queryForList("SELECT tls_inbound_id FROM forward WHERE id=?",dto.getId());
            var ports=jdbc.queryForList("SELECT DISTINCT port FROM forward_port WHERE forward_id=?",Integer.class,dto.getId());
            if(ports.size()!=1)error="原入口端口不一致或缺失，请逐条编辑";
            else if(!original.isEmpty()&&original.getFirst().get("tls_inbound_id") instanceof Number inbound&&inbound.intValue()>0&&!ports.getFirst().equals(dto.getInPort()))error="共享 TLS 规则不能单独修改入口端口";
            if(error==null)try {R result=service.updateForward(dto);if(result.getCode()!=0)error=result.getMsg();}catch(RuntimeException ex){error=ex.getMessage()==null?"修改失败":ex.getMessage();}
            if(error==null)succeeded.add(dto.getId());else failures.add(Map.of("id",dto.getId(),"line",i+1,"name",dto.getName(),"reason",error));
        }
        return R.ok(Map.of("count",succeeded.size(),"failures",failures));
    }
    public static boolean validRemote(String remote) {
        if(remote.isEmpty())return false;
        for(String address:remote.split(",",-1)) {
            String host;String port;address=address.trim();
            int colon=address.lastIndexOf(':');if(colon<=0)return false;
            host=address.substring(0,colon);port=address.substring(colon+1);
            if(!(host.matches("[A-Za-z0-9_.-]+")||host.matches("\\[[0-9a-fA-F:]+\\]")))return false;
            try{int n=Integer.parseInt(port);if(n<1||n>65535)return false;}catch(NumberFormatException ex){return false;}
        }
        return true;
    }
}
