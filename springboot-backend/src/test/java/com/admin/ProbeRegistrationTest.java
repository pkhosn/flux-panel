package com.admin;

import com.admin.service.probe.ProbeRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ProbeRegistrationTest {
    @Test void tokensExpireCannotReplayAndOwnerFilterHasNoAdminBypass() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE probe_registration(token_hash TEXT PRIMARY KEY,user_id INTEGER,expires_at INTEGER)");
            jdbc.execute("CREATE TABLE node(id INTEGER,name TEXT,status INTEGER,server_ip TEXT,detected_ipv4 TEXT,detected_ipv6 TEXT,version TEXT,probe_mode INTEGER,owner_user_id INTEGER)");
            jdbc.update("INSERT INTO node(id,probe_mode,owner_user_id) VALUES(1,1,11),(2,1,22),(3,0,11)");
            var service = new ProbeRegistrationService(jdbc);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            String token = tx.execute(s -> service.issue(11));
            assertNotEquals(token,jdbc.queryForObject("SELECT token_hash FROM probe_registration",String.class));
            assertEquals(Integer.valueOf(11),tx.execute(s -> service.consume(token)));
            assertThrows(IllegalArgumentException.class,() -> tx.execute(s -> service.consume(token)));
            String expired = tx.execute(s -> service.issue(22));
            jdbc.update("UPDATE probe_registration SET expires_at=0");
            assertThrows(IllegalArgumentException.class,() -> tx.execute(s -> service.consume(expired)));
            assertThrows(IllegalArgumentException.class,() -> service.consume("invalid"));
            assertEquals(1,service.listOwned(11).size());
            assertTrue(service.listOwned(0).isEmpty());
            service.requireOwner(1,11);
            assertThrows(IllegalArgumentException.class,() -> service.requireOwner(1,22));
            assertThrows(IllegalArgumentException.class,() -> service.requireOwner(1,0));
            assertThrows(IllegalArgumentException.class,() -> service.requireOwner(3,11));
        } finally {ds.destroy();}
    }
}
