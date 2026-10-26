package com.admin.service.probe;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.service.UserNodePermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

@Service
public class ProbeActivationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final UserNodePermissionService permissions;
    public ProbeActivationService(JdbcTemplate jdbc, PlatformTransactionManager manager, UserNodePermissionService permissions) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager); this.permissions=permissions;
    }

    // No DB transaction is held while waiting for the Agent.
    public synchronized R activate(long id, int owner, String name, String ports) {
        if (name==null || name.isBlank() || name.length()>100 || !validPorts(ports)) return R.err("名称或端口范围无效");
        var rows=jdbc.queryForList("SELECT probe_mode,status,version FROM node WHERE id=? AND owner_user_id=?",id,owner);
        if (rows.isEmpty()) return R.err("探针不存在或无权限");
        var row=rows.getFirst();
        if (((Number)row.get("probe_mode")).intValue()==0) return R.ok();
        if (((Number)row.get("status")).intValue()!=1) return R.err("探针不在线，请上线后重试");
        if (!supportsActivation((String)row.get("version"))) return R.err("请先升级探针 Agent 至 2.0.17-beta");
        GostDto enabled=send(id,"EnableForwarding");
        if (!"OK".equals(enabled.getMsg())) return R.err("启用失败："+enabled.getMsg());
        boolean ready=false;
        for (int attempt=0;attempt<10;attempt++) {
            GostDto state=send(id,"ForwardingStatus");
            if ("OK".equals(state.getMsg()) && state.getData() instanceof Map<?,?> data && Boolean.TRUE.equals(data.get("ready"))) {
                ready=true; break;
            }
            if (!"OK".equals(state.getMsg())) return R.err("检查转发状态失败："+state.getMsg());
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return R.err("启用已中断，请重试"); }
        }
        if (!ready) return R.err("转发尚未就绪，请稍后重试；探针身份已保留");
        return transaction.execute(status -> {
            int changed=jdbc.update("UPDATE node SET name=?,port=?,probe_mode=0,updated_time=? WHERE id=? AND owner_user_id=? AND probe_mode=1",
                    name.trim(),ports.trim(),System.currentTimeMillis(),id,owner);
            if(changed!=1) return R.err("探针状态已变化，请刷新后重试");
            permissions.grantNodeOwnerPermissions(owner,id);
            return R.ok();
        });
    }

    protected GostDto send(long id,String command) { return WebSocketServer.send_msg(id,Map.of(),command); }

    static boolean supportsActivation(String version) {
        if(version==null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+-beta")) return false;
        String[] parts=version.replace("-beta","").split("\\.");
        try {
            int major=Integer.parseInt(parts[0]),minor=Integer.parseInt(parts[1]),patch=Integer.parseInt(parts[2]);
            return major>2 || (major==2 && (minor>0 || patch>=17));
        } catch(NumberFormatException invalid) { return false; }
    }

    static boolean validPorts(String value) {
        if(value==null || value.length()>1000 || value.isBlank()) return false;
        for(String part:value.split(",",-1)) {
            if(!part.trim().matches("[0-9]{1,5}(-[0-9]{1,5})?")) return false;
            String[] range=part.trim().split("-");
            int first=Integer.parseInt(range[0]),last=Integer.parseInt(range[range.length-1]);
            if(first<1 || last>65535 || first>last) return false;
        }
        return true;
    }
}
