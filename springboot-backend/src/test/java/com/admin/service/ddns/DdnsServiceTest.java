package com.admin.service.ddns;

import com.admin.controller.DdnsController;
import com.admin.common.aop.RoleAspect;
import com.admin.common.annotation.RequireRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DdnsServiceTest {
    static final String Z = "a".repeat(32), R = "b".repeat(32), OTHER = "c".repeat(32), TOKEN = "test_cloudflare_token_12345678";
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    DdnsRemote remote;
    DdnsService service;
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true); jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE ddns_account(owner_user_id INTEGER PRIMARY KEY,zone_id TEXT,token_cipher TEXT)");
        jdbc.execute("CREATE TABLE ddns_task(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,source TEXT,zone_id TEXT,record_id TEXT,record_name TEXT,enabled INTEGER,source_ip TEXT,target_ip TEXT,checked_at INTEGER,updated_at INTEGER,error TEXT DEFAULT '',owner_user_id INTEGER DEFAULT 1,interval_minutes INTEGER DEFAULT 5,UNIQUE(zone_id,record_id))");
        remote = mock(DdnsRemote.class); service = new DdnsService(jdbc, remote, new DdnsCrypto("test-panel-secret"));
        when(remote.zone(Z,TOKEN)).thenReturn("example.com");
        service.saveAccount(1, Z,TOKEN);
        when(remote.record(Z,R,TOKEN)).thenReturn(mapper.valueToTree(Map.of("id",R,"type","A","name","in.example.com","content","8.8.8.8","proxied",false)));
        when(remote.resolve("a.example.com")).thenReturn("1.1.1.1");
    }
    @AfterEach void cleanup() { service.close(); ds.destroy(); RequestContextHolder.resetRequestAttributes(); }
    long create() { service.saveTask(1, null,"HiNet A","a.example.com",R,true,5); return jdbc.queryForObject("SELECT id FROM ddns_task",Long.class); }
    @Test void updatesOnlyBoundRecordAndSkipsUnchanged() {
        long id=create();
        jdbc.update("INSERT INTO ddns_task(name,source,zone_id,record_id,record_name,enabled,target_ip) VALUES('other','b.example.com',?,?,'in.example.com',0,'9.9.9.9')",Z,OTHER);
        service.syncNow(1, id);
        verify(remote).update(Z,R,TOKEN,"1.1.1.1");
        assertEquals("1.1.1.1",jdbc.queryForObject("SELECT target_ip FROM ddns_task WHERE id=?",String.class,id));
        assertEquals("9.9.9.9",jdbc.queryForObject("SELECT target_ip FROM ddns_task WHERE record_id=?",String.class,OTHER));
        assertNotNull(jdbc.queryForObject("SELECT updated_at FROM ddns_task WHERE id=?",Long.class,id));
        when(remote.resolve("a.example.com")).thenReturn("8.8.8.8");service.syncNow(1, id);
        verify(remote,times(1)).update(anyString(),anyString(),anyString(),anyString());
        assertEquals("",jdbc.queryForObject("SELECT error FROM ddns_task WHERE id=?",String.class,id));
    }
    @Test void failuresKeepDnsAndAllowRetry() {
        long id=create();
        when(remote.resolve("a.example.com")).thenThrow(new IllegalStateException("来源解析失败"));service.syncNow(1, id);
        verify(remote,never()).update(anyString(),anyString(),anyString(),anyString());
        assertEquals("8.8.8.8",jdbc.queryForObject("SELECT target_ip FROM ddns_task",String.class));
        assertEquals("来源解析失败",jdbc.queryForObject("SELECT error FROM ddns_task",String.class));
        doReturn("1.1.1.1").when(remote).resolve("a.example.com");
        doThrow(new IllegalStateException("远程服务返回 HTTP 403")).when(remote).update(Z,R,TOKEN,"1.1.1.1");service.syncNow(1, id);
        assertNull(jdbc.queryForObject("SELECT updated_at FROM ddns_task",Long.class));
        doNothing().when(remote).update(Z,R,TOKEN,"1.1.1.1");service.syncNow(1, id);
        assertEquals("",jdbc.queryForObject("SELECT error FROM ddns_task",String.class));
    }
    @Test void changedRecordAndProxyAreRejectedAndDeletionNeverDeletesDns() {
        long id=create();
        when(remote.record(Z,R,TOKEN)).thenReturn(mapper.valueToTree(Map.of("type","A","name","changed.example.com","content","8.8.8.8","proxied",false)));
        service.syncNow(1, id); verify(remote,never()).update(anyString(),anyString(),anyString(),anyString());
        when(remote.record(Z,R,TOKEN)).thenReturn(mapper.valueToTree(Map.of("type","A","name","in.example.com","content","8.8.8.8","proxied",true)));
        service.syncNow(1, id); verify(remote,never()).update(anyString(),anyString(),anyString(),anyString());
        service.delete(1, id); assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ddns_task",Integer.class));
    }
    @Test void bindingsCredentialPrivacyValidationAndPause() throws Exception {
        long id=create();
        assertThrows(IllegalArgumentException.class,()->service.saveTask(1, null,"duplicate","b.example.com",R,true,5));
        assertThrows(IllegalArgumentException.class,()->service.saveTask(1, null,"bad","https://a.example.com/path",OTHER,true,5));
        String cipher=jdbc.queryForObject("SELECT token_cipher FROM ddns_account",String.class);
        assertFalse(cipher.contains(TOKEN));assertEquals(TOKEN,new DdnsCrypto("test-panel-secret").decrypt(cipher));
        assertThrows(IllegalStateException.class,()->new DdnsCrypto("other-key").decrypt(cipher));
        assertFalse(mapper.writeValueAsString(service.list(1)).contains(TOKEN));
        service.saveAccount(1, Z,"");
        service.enable(1, id,false);service.syncNow(1, id);verify(remote,never()).resolve(anyString());
        assertThrows(IllegalArgumentException.class,()->service.sync(1, id));
        assertThrows(IllegalArgumentException.class,()->service.saveAccount(1, OTHER,TOKEN));
    }
    @Test void queuedSyncRejectsDuplicatesAndConfigurationRaces() throws Exception {
        long id=create();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(remote.resolve("a.example.com")).thenAnswer(invocation->{
            entered.countDown();
            if (!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return "1.1.1.1";
        });
        try {
            service.sync(1, id);
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->service.sync(1, id));
            assertThrows(IllegalStateException.class,()->service.delete(1, id));
            assertThrows(IllegalStateException.class,()->service.enable(1, id,false));
        } finally { release.countDown(); }
        for(int i=0;i<100 && jdbc.queryForObject("SELECT checked_at FROM ddns_task",Long.class)==null;i++) Thread.sleep(10);
        assertNotNull(jdbc.queryForObject("SELECT checked_at FROM ddns_task",Long.class));
        verify(remote,times(1)).update(Z,R,TOKEN,"1.1.1.1");
    }
    @Test void controllerRejectsMissingIdentityAndScopesLoggedInUser() {
        DdnsController controller=new DdnsController(service);
        assertNotEquals(0,controller.list().getCode());
        var request=new MockHttpServletRequest();
        request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":2,\"role_id\":1}".getBytes())+".s");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals(0,controller.list().getCode());
        assertEquals(false,((Map<?,?>)controller.list().getData()).get("configured"));
        assertNotEquals(0,controller.delete(Map.of("id",create())).getCode());
    }
    @Test void ownershipAppliesToEveryOperationAndGlobalBindingIsUnique() {
        long id=create();
        String second="second_cloudflare_token_123456";
        service.saveAccount(2,Z,second);
        assertEquals(0,((List<?>)service.list(2).get("tasks")).size());
        assertThrows(IllegalArgumentException.class,()->service.delete(2,id));
        assertThrows(IllegalArgumentException.class,()->service.enable(2,id,false));
        assertThrows(IllegalArgumentException.class,()->service.sync(2,id));
        assertThrows(IllegalArgumentException.class,()->service.saveTask(2,id,"hijack","b.example.com",R,true,5));
        var record=remote.record(Z,R,TOKEN);
        when(remote.record(Z,R,second)).thenReturn(record);
        assertThrows(IllegalArgumentException.class,()->service.saveTask(2,null,"duplicate","b.example.com",R,true,5));
        assertThrows(IllegalArgumentException.class,()->service.saveTask(1,id,"bad interval","a.example.com",R,true,2));
        service.syncNow(1,id);verify(remote).update(Z,R,TOKEN,"1.1.1.1");
        verify(remote,never()).update(eq(Z),eq(R),eq(second),anyString());
        assertEquals(TOKEN,new DdnsCrypto("test-panel-secret").decrypt(jdbc.queryForObject("SELECT token_cipher FROM ddns_account WHERE owner_user_id=1",String.class)));
    }
    @Test void schedulerSkipsRecentChecksButManualSyncBypassesInterval() throws Exception {
        long id=create();
        jdbc.update("UPDATE ddns_task SET checked_at=?",System.currentTimeMillis());
        service.schedule();Thread.sleep(100);
        verify(remote,never()).resolve(anyString());
        service.sync(1,id);
        for(int i=0;i<100 && jdbc.queryForObject("SELECT updated_at FROM ddns_task",Long.class)==null;i++)Thread.sleep(10);
        verify(remote,times(1)).resolve("a.example.com");
        jdbc.update("UPDATE ddns_task SET checked_at=?",System.currentTimeMillis()-6*60000);
        service.schedule();
        for(int i=0;i<100 && jdbc.queryForObject("SELECT checked_at FROM ddns_task",Long.class)<System.currentTimeMillis()-60000;i++)Thread.sleep(10);
        verify(remote,times(2)).resolve("a.example.com");
    }
    @Test void oldDatabaseMigrationPreservesCredentialsTasksAndIsIdempotent() throws Exception {
        var old=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var db=new JdbcTemplate(old);
            db.execute("CREATE TABLE user(id INTEGER PRIMARY KEY,role_id INTEGER)");
            db.update("INSERT INTO user VALUES(3,1),(7,0),(9,0)");
            db.execute("CREATE TABLE ddns_account(id INTEGER PRIMARY KEY CHECK(id=1),zone_id TEXT,token_cipher TEXT)");
            db.update("INSERT INTO ddns_account VALUES(1,?,?)",Z,"encrypted-existing-token");
            db.execute("CREATE TABLE ddns_task(id INTEGER PRIMARY KEY,zone_id TEXT,record_id TEXT,UNIQUE(zone_id,record_id))");
            db.update("INSERT INTO ddns_task VALUES(22,?,?)",Z,R);
            var config=new com.admin.config.SQLiteConfig(old);
            var migrate=com.admin.config.SQLiteConfig.class.getDeclaredMethod("ensureDdnsOwnership",java.sql.Connection.class);
            migrate.setAccessible(true);
            migrate.invoke(config,old.getConnection());migrate.invoke(config,old.getConnection());
            assertEquals(7,db.queryForObject("SELECT owner_user_id FROM ddns_account",Integer.class));
            assertEquals("encrypted-existing-token",db.queryForObject("SELECT token_cipher FROM ddns_account",String.class));
            assertEquals(7,db.queryForObject("SELECT owner_user_id FROM ddns_task",Integer.class));
            assertEquals(5,db.queryForObject("SELECT interval_minutes FROM ddns_task",Integer.class));
            db.update("INSERT INTO ddns_account VALUES(3,?,?)",Z,"second-token");
            migrate.invoke(config,old.getConnection());
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ddns_account",Integer.class));
        } finally { old.destroy(); }
    }
    @Test void dnsMustReturnExactlyOnePublicAddressAndPatchOnlyContent() throws Exception {
        class Fake extends DdnsRemote {
            String response; Map<String,Object> patch;String url;
            @Override protected com.fasterxml.jackson.databind.JsonNode request(String url,String token,Map<String,Object> patch) {
                this.patch=patch;this.url=url;
                try{return mapper.readTree(response);}catch(Exception e){throw new RuntimeException(e);}
            }
        }
        var fake=new Fake();
        fake.response="{\"Status\":0,\"Answer\":[{\"type\":5,\"data\":\"other.example.com\"},{\"type\":1,\"data\":\"1.1.1.1\"}]}";
        assertEquals("1.1.1.1",fake.resolve("a.example.com"));
        fake.response="{\"Status\":0,\"Answer\":[{\"type\":1,\"data\":\"1.1.1.1\"},{\"type\":1,\"data\":\"8.8.8.8\"}]}";
        assertThrows(IllegalStateException.class,()->fake.resolve("a.example.com"));
        fake.response="{\"Status\":3}";assertThrows(IllegalStateException.class,()->fake.resolve("a.example.com"));
        for(String value:List.of("10.0.0.1","127.0.0.1","192.168.1.1","172.16.1.1","169.254.1.1","100.64.1.1","999.1.1.1"))assertFalse(DdnsRemote.publicIpv4(value));
        fake.response="{}";fake.update(Z,R,TOKEN,"1.1.1.1");assertEquals(Map.of("content","1.1.1.1"),fake.patch);
        assertTrue(fake.url.endsWith("/"+R));
    }
}
