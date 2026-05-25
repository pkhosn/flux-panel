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
        ensureColumn(connection, "node", "owner_user_id", "INTEGER");
        ensureColumn(connection, "node", "created_by_role", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, "tunnel", "owner_user_id", "INTEGER");
        ensureColumn(connection, "tunnel", "created_by_role", "INTEGER NOT NULL DEFAULT 0");
        ensureUserNodePermissionTable(connection);
        ensurePlanTable(connection);
        ensureOrderTable(connection);
        ensureRedeemCodeTable(connection);
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
