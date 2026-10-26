package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.service.impl.ForwardServiceImpl;
import com.alibaba.fastjson.JSONArray;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.TunnelUpdateDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.ForwardImportDto;

/** A durable pre-operation snapshot makes failed or interrupted operations recoverable. */
@Aspect
@Component
public class ForwardRecovery {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    @Autowired @Lazy private ForwardServiceImpl forwards;
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private static final Object LOCK = new Object();

    public ForwardRecovery(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Around("execution(* com.admin.service.impl.TunnelServiceImpl.updateTunnel(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.createForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.updateForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.deleteForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.forceDeleteForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.pauseForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.resumeForward(..)) || " +
            "execution(* com.admin.service.impl.ForwardServiceImpl.importForwards(..)) || " +
            "execution(* com.admin.controller.ForwardBatchController.batch(..)) || " +
            "execution(* com.admin.controller.ForwardTlsInboundController.update(..)) || " +
            "execution(* com.admin.controller.ForwardTlsInboundController.policy(..)) || " +
            "execution(* com.admin.controller.ForwardTlsInboundController.toggle(..))")
    public Object around(ProceedingJoinPoint call) throws Throwable {
        if (Boolean.TRUE.equals(ACTIVE.get())) return call.proceed();
        synchronized (LOCK) {
            if (pending()) {
                String method = call.getSignature().getName();
                if (("deleteForward".equals(method) || "forceDeleteForward".equals(method))
                        && call.getArgs().length == 1 && call.getArgs()[0] instanceof Long id) {
                    return deleteDuringRecovery(id);
                }
                return R.err("上次规则操作尚未恢复，可能存在端口冲突或节点离线；可先删除冲突规则后重试");
            }
            if (call.getArgs().length > 0 && call.getArgs()[0] instanceof Map<?, ?> input
                    && ("group".equals(input.get("action")) || "clear".equals(input.get("action")))) {
                return transaction.execute(status -> {
                    try { return call.proceed(); } catch (Throwable failure) { throw new OperationFailure(failure); }
                });
            }
            Set<Long> nodes = operationNodes(call.getArgs(), call.getSignature().getDeclaringTypeName());
            for (Long node : nodes) {
                String version = jdbc.queryForObject("SELECT version FROM node WHERE id=?", String.class, node);
                if (!supportsRecovery(version)) return R.err("请先将入口节点 " + node + " 的 Agent 升级至 2.0.16-beta，以支持故障自动恢复");
            }
            boolean force = "forceDeleteForward".equals(call.getSignature().getName());
            Map<Long, JSONArray> snapshot = forwards.recoveryServices(nodes);
            transaction.executeWithoutResult(status -> {
                for (Long node : nodes) {
                    jdbc.update("INSERT INTO forward_recovery(node_id,services) VALUES(?,?)", node,
                            snapshot.getOrDefault(node, new JSONArray()).toJSONString());
                }
            });
            ACTIVE.set(true);
            try {
                Object result = transaction.execute(status -> {
                    try {
                        Object value = call.proceed();
                        if (value instanceof R response && response.getCode() != 0) status.setRollbackOnly();
                        else if (force) {
                            Map<Long, JSONArray> desired = forwards.recoveryServices(nodes);
                            for (Long node : nodes) jdbc.update("UPDATE forward_recovery SET services=? WHERE node_id=?",
                                    desired.getOrDefault(node, new JSONArray()).toJSONString(), node);
                        } else jdbc.update("DELETE FROM forward_recovery");
                        return value;
                    } catch (Throwable failure) { throw new OperationFailure(failure); }
                });
                if (force) restore();
                if (result instanceof R response && response.getCode() != 0) {
                    restore();
                    if (pending()) response.setMsg(response.getMsg() + "；恢复任务已持久化，将在节点上线后重试");
                }
                return result;
            } catch (RuntimeException failure) {
                restore();
                return R.err("规则操作失败，数据库已回退" + (pending() ? "；节点恢复任务已保存，将自动重试" : "；节点已恢复"));
            } finally { ACTIVE.remove(); }
        }
    }

    private R deleteDuringRecovery(Long id) {
        Set<Long> nodes = new HashSet<>(jdbc.queryForList("SELECT node_id FROM forward_recovery", Long.class));
        addRuleNodes(nodes, id);
        ACTIVE.set(true);
        try {
            R result = transaction.execute(status -> {
                // 先取得写锁；强制删除仍执行原有用户归属校验。
                jdbc.update("UPDATE forward_recovery SET services=services");
                R deleted = forwards.forceDeleteForward(id);
                if (deleted.getCode() != 0) { status.setRollbackOnly(); return deleted; }
                Map<Long, JSONArray> desired = forwards.recoveryServices(nodes);
                for (Long node : nodes) {
                    jdbc.update("INSERT OR REPLACE INTO forward_recovery(node_id,services) VALUES(?,?)",
                            node, desired.getOrDefault(node, new JSONArray()).toJSONString());
                }
                return deleted;
            });
            if (result.getCode() == 0) {
                restore();
                if (pending()) result.setMsg("规则已删除；部分节点仍在同步，请检查端口冲突或节点连接");
            }
            return result;
        } finally { ACTIVE.remove(); }
    }

    private Set<Long> operationNodes(Object[] args, String owner) {
        Set<Long> nodes = new HashSet<>();
        for (Object input : args) {
            if (input instanceof TunnelUpdateDto tunnel) {
                addTunnelNodes(nodes, tunnel.getId().intValue());
                if (tunnel.getInNodeId() != null) tunnel.getInNodeId().forEach(entry -> nodes.add(entry.getNodeId()));
            } else if (input instanceof ForwardImportDto batch) {
                if (batch.getRules() != null) nodes.addAll(operationNodes(batch.getRules().toArray(), owner));
            } else if (input instanceof ForwardDto rule) {
                addTunnelNodes(nodes, rule.getTunnelId());
            } else if (input instanceof ForwardUpdateDto rule) {
                addRuleNodes(nodes, rule.getId());
                addTunnelNodes(nodes, rule.getTunnelId());
            } else if (input instanceof Number id) addRuleNodes(nodes, id.longValue());
            else if (input instanceof Map<?, ?> map) {
                if (owner.endsWith("ForwardTlsInboundController") && map.get("id") != null) {
                    for (Integer tunnel : jdbc.queryForList("SELECT tunnel_id FROM forward_tls_inbound WHERE id=?", Integer.class, map.get("id"))) addTunnelNodes(nodes, tunnel);
                } else if (map.get("ids") instanceof java.util.List<?> ids) {
                    for (Object id : ids) addRuleNodes(nodes, Long.valueOf(id.toString()));
                    if (map.get("tunnelId") != null) addTunnelNodes(nodes, Integer.valueOf(map.get("tunnelId").toString()));
                }
            }
        }
        return nodes;
    }

    private void addRuleNodes(Set<Long> nodes, Long rule) {
        nodes.addAll(jdbc.queryForList("SELECT node_id FROM forward_port WHERE forward_id=?", Long.class, rule));
        for (Integer tunnel : jdbc.queryForList("SELECT tunnel_id FROM forward WHERE id=?", Integer.class, rule)) addTunnelNodes(nodes, tunnel);
    }

    private void addTunnelNodes(Set<Long> nodes, Integer tunnel) {
        if (tunnel != null) nodes.addAll(jdbc.queryForList("SELECT node_id FROM chain_tunnel WHERE tunnel_id=? AND chain_type=1", Long.class, tunnel));
    }

    static boolean supportsRecovery(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+-beta")) return false;
        String[] parts = version.replace("-beta", "").split("\\.");
        int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]), patch = Integer.parseInt(parts[2]);
        return major > 2 || (major == 2 && (minor > 0 || patch >= 16));
    }

    public boolean pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM forward_recovery", Integer.class) > 0;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void restore() {
        synchronized (LOCK) {
            for (var row : jdbc.queryForList("SELECT node_id,services FROM forward_recovery ORDER BY node_id")) {
                Long node = ((Number) row.get("node_id")).longValue();
                try {
                    var result = sendRecovery(node, JSONArray.parseArray((String) row.get("services")));
                    if ("OK".equals(result.getMsg())) jdbc.update("DELETE FROM forward_recovery WHERE node_id=?", node);
                } catch (RuntimeException ignored) { /* Leave the durable task for the next retry. */ }
            }
        }
    }

    protected com.admin.common.dto.GostDto sendRecovery(Long node, JSONArray services) {
        return WebSocketServer.send_msg(node, services, "ReconcileForwards");
    }

    private static class OperationFailure extends RuntimeException {
        OperationFailure(Throwable cause) { super(cause); }
    }
}
