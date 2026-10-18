package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite 数据库配置
 * 启用 WAL (Write-Ahead Logging) 模式以提高并发性能
 * 添加定期 checkpoint 和优雅关闭处理
 */
@Slf4j
@Component
@EnableScheduling
public class SQLiteConfig implements ApplicationRunner {

    private final DataSource dataSource;

    public SQLiteConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA cache_size=-64000;"); // 64MB 缓存
            statement.execute("PRAGMA temp_store=MEMORY;");
            statement.execute("PRAGMA busy_timeout=5000;"); // 5秒超时
            statement.execute("PRAGMA wal_autocheckpoint=1000;"); // 每1000页自动checkpoint
            ensureSchema(connection);
            
            log.info("SQLite WAL mode configured successfully");
        } catch (Exception e) {
            log.error("Failed to configure SQLite database", e);
            throw e;
        }
    }
    
    /**
     * 定期执行 checkpoint，确保 WAL 文件内容写入主数据库
     * 每5分钟执行一次
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void performCheckpoint() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            log.debug("SQLite WAL checkpoint completed");
        } catch (Exception e) {
            log.error("Failed to perform SQLite checkpoint", e);
        }
    }
    
    /**
     * 应用关闭前执行最终的 checkpoint，确保所有数据都写入主数据库文件
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Performing final SQLite checkpoint before shutdown...");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            
            // 强制执行 checkpoint，将所有 WAL 内容写入主数据库
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            log.info("Final SQLite checkpoint completed successfully");
        } catch (Exception e) {
            log.error("Failed to perform final SQLite checkpoint", e);
        }
    }

    private void ensureSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS forward_recovery (node_id INTEGER PRIMARY KEY, services TEXT NOT NULL)");
        }
        ensureColumn(connection, "forward", "protocol", "VARCHAR(10) NOT NULL DEFAULT 'tcp_udp'");
        ensureColumn(connection, "forward", "tls_inbound_id", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "forward", "sni_names", "TEXT NOT NULL DEFAULT ''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS forward_tls_inbound (id INTEGER PRIMARY KEY AUTOINCREMENT, tunnel_id INTEGER NOT NULL, name TEXT NOT NULL, port INTEGER NOT NULL, allowed_sni TEXT NOT NULL DEFAULT '', UNIQUE(tunnel_id,port))");
        }
        ensureColumn(connection, "forward_tls_inbound", "policy", "TEXT NOT NULL DEFAULT '{}'");
        ensureColumn(connection, "node", "group_name", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "node", "tls_only", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "forward_tls_inbound", "enabled", "INTEGER NOT NULL DEFAULT 1");
        ensureColumn(connection, "forward_tls_inbound", "suspended_ids", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "forward", "origin_tls", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "forward", "origin_server_name", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "forward", "origin_root_ca", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "forward", "origin_alpn", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "forward", "proxy_accept", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "forward", "proxy_send", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "forward", "proxy_trusted_cidrs", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, "forward", "group_id", "INTEGER");
        ensureColumn(connection, "forward", "traffic_baseline", "INTEGER NOT NULL DEFAULT 0");
        ensureForwardGroupTable(connection);
        ensureForwardFlowHourTable(connection);
        ensureColumn(connection, "node", "owner_user_id", "INTEGER");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS node_group (user_id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(user_id,name))");
            statement.execute("INSERT OR IGNORE INTO node_group(user_id,name) SELECT COALESCE(owner_user_id,0),group_name FROM node WHERE group_name<>''");
        }

        ensureColumn(connection, "node", "created_by_role", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "node", "detected_ipv4", "VARCHAR(100)");
        ensureColumn(connection, "node", "detected_ipv6", "VARCHAR(100)");
        ensureColumn(connection, "tunnel", "owner_user_id", "INTEGER");
        ensureColumn(connection, "tunnel", "created_by_role", "INTEGER NOT NULL DEFAULT 0");
        ensureUserNodePermissionTable(connection);
        ensurePlanTable(connection);
        ensureOrderTable(connection);
        ensureRedeemCodeTable(connection);
    }

    private void ensureForwardGroupTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS forward_group (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER NOT NULL," +
                    "name VARCHAR(64) NOT NULL," +
                    "UNIQUE(user_id, name))");
        }
    }

    private void ensureForwardFlowHourTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS forward_flow_hour (" +
                    "forward_id INTEGER NOT NULL," +
                    "user_id INTEGER NOT NULL," +
                    "hour_start INTEGER NOT NULL," +
                    "bytes INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (forward_id, hour_start))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_forward_flow_hour_user_time " +
                    "ON forward_flow_hour(user_id, hour_start)");
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws Exception {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Schema migration applied: {}.{}", table, column);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureUserNodePermissionTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS user_node_permission (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "user_id INTEGER NOT NULL," +
                    "node_id INTEGER NOT NULL," +
                    "allow_in INTEGER NOT NULL DEFAULT 0," +
                    "allow_out INTEGER NOT NULL DEFAULT 0," +
                    "created_time INTEGER NOT NULL," +
                    "updated_time INTEGER," +
                    "status INTEGER NOT NULL DEFAULT 1," +
                    "UNIQUE(user_id, node_id))");
        }
    }

    private void ensurePlanTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS plan (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_time INTEGER NOT NULL," +
                    "updated_time INTEGER," +
                    "status INTEGER NOT NULL DEFAULT 1," +
                    "name TEXT NOT NULL," +
                    "description TEXT," +
                    "price INTEGER NOT NULL DEFAULT 0," +
                    "flow_gb INTEGER NOT NULL DEFAULT 0," +
                    "forward_num INTEGER NOT NULL DEFAULT 0," +
                    "duration_days INTEGER NOT NULL DEFAULT 0," +
                    "stock INTEGER NOT NULL DEFAULT 0)");
        }
    }

    private void ensureOrderTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS order_record (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_time INTEGER NOT NULL," +
                    "updated_time INTEGER," +
                    "status INTEGER NOT NULL DEFAULT 0," +
                    "order_no TEXT NOT NULL UNIQUE," +
                    "user_id INTEGER NOT NULL," +
                    "user_name TEXT NOT NULL," +
                    "plan_id INTEGER NOT NULL," +
                    "plan_name TEXT NOT NULL," +
                    "amount INTEGER NOT NULL DEFAULT 0," +
                    "pay_type TEXT," +
                    "paid_time INTEGER," +
                    "pay_url TEXT)");
        }
    }

    private void ensureRedeemCodeTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS redeem_code (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_time INTEGER NOT NULL," +
                    "updated_time INTEGER," +
                    "status INTEGER NOT NULL DEFAULT 1," +
                    "code TEXT NOT NULL UNIQUE," +
                    "flow_gb INTEGER NOT NULL DEFAULT 0," +
                    "forward_num INTEGER NOT NULL DEFAULT 0," +
                    "duration_days INTEGER NOT NULL DEFAULT 0," +
                    "used INTEGER NOT NULL DEFAULT 0," +
                    "used_by INTEGER," +
                    "used_time INTEGER)");
        }
    }
}
