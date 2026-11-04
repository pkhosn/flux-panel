package com.admin;

import com.admin.common.dto.GostDto;
import com.admin.service.probe.ProbeActivationService;
import com.admin.service.UserNodePermissionService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProbeDeactivationTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    TestActivation service;
    class TestActivation extends ProbeActivationService {
        boolean fail, unconfirmed; int commands;
        TestActivation() { super(jdbc,new DataSourceTransactionManager(ds),mock(UserNodePermissionService.class)); }
        @Override protected GostDto send(long id,String command) {
            commands++;
            assertEquals(2,jdbc.queryForObject("SELECT probe_mode FROM node WHERE id=?",Integer.class,id));
            var result=new GostDto();result.setMsg(fail ? "timeout" : "OK");
            result.setData(Map.of("ready",unconfirmed,"probeMode",!unconfirmed));return result;
        }
    }
    @BeforeEach void setup() {
        ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE node(id INTEGER PRIMARY KEY,probe_mode INTEGER,status INTEGER,version TEXT,owner_user_id INTEGER,group_name TEXT,updated_time INTEGER)");
        jdbc.execute("CREATE TABLE tunnel(id INTEGER PRIMARY KEY,name TEXT)");
        jdbc.execute("CREATE TABLE chain_tunnel(node_id INTEGER,tunnel_id INTEGER)");
        jdbc.execute("CREATE TABLE forward_port(node_id INTEGER)");
        jdbc.execute("CREATE TABLE forward_recovery(node_id INTEGER)");
        jdbc.execute("CREATE TABLE user_node_permission(node_id INTEGER,user_id INTEGER)");
        jdbc.update("INSERT INTO node VALUES(1,0,1,'2.0.18-beta',NULL,'group',0),(2,0,1,'2.0.18-beta',22,'group',0)");
        jdbc.update("INSERT INTO user_node_permission VALUES(1,33)");service=new TestActivation();
    }
    @AfterEach void close(){ds.destroy();}
    @Test void refusesOtherOwnersOfflineAndOldAgents() {
        assertNotEquals(0,service.deactivate(1,11,false).getCode());
        assertNotEquals(0,service.deactivate(2,11,true).getCode());
        jdbc.update("UPDATE node SET status=0 WHERE id=1");assertNotEquals(0,service.deactivate(1,11,true).getCode());
        jdbc.update("UPDATE node SET status=1,version='2.0.17-beta' WHERE id=1");assertNotEquals(0,service.deactivate(1,11,true).getCode());
        assertEquals(0,service.commands);
    }
    @Test void tunnelReferencesPortsAndRecoveryBlockWithoutSending() {
        jdbc.update("INSERT INTO tunnel VALUES(3,'active-tunnel')");jdbc.update("INSERT INTO chain_tunnel VALUES(1,3)");
        assertTrue(service.deactivate(1,11,true).getMsg().contains("active-tunnel"));
        jdbc.update("DELETE FROM chain_tunnel");jdbc.update("INSERT INTO forward_port VALUES(1)");
        assertNotEquals(0,service.deactivate(1,11,true).getCode());jdbc.update("DELETE FROM forward_port");
        jdbc.update("INSERT INTO forward_recovery VALUES(1)");assertNotEquals(0,service.deactivate(1,11,true).getCode());
        assertEquals(0,service.commands);assertEquals(0,jdbc.queryForObject("SELECT probe_mode FROM node WHERE id=1",Integer.class));
    }
    @Test void successAssignsPublicNodeToOperatorAndRevokesSharedPermissions() {
        assertEquals(0,service.deactivate(1,11,true).getCode());
        assertEquals(1,jdbc.queryForObject("SELECT probe_mode FROM node WHERE id=1",Integer.class));
        assertEquals(11,jdbc.queryForObject("SELECT owner_user_id FROM node WHERE id=1",Integer.class));
        assertEquals("",jdbc.queryForObject("SELECT group_name FROM node WHERE id=1",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_node_permission",Integer.class));
        assertEquals(0,service.deactivate(1,11,false).getCode());assertEquals(2,service.commands);
    }
    @Test void responseLossAndUnconfirmedStateRemainReservedAndRetryable() {
        service.fail=true;assertNotEquals(0,service.deactivate(2,22,false).getCode());
        assertEquals(2,jdbc.queryForObject("SELECT probe_mode FROM node WHERE id=2",Integer.class));
        service.fail=false;service.unconfirmed=true;assertNotEquals(0,service.deactivate(2,22,false).getCode());
        assertEquals(2,jdbc.queryForObject("SELECT probe_mode FROM node WHERE id=2",Integer.class));
        service.unconfirmed=false;assertEquals(0,service.deactivate(2,22,false).getCode());
    }
}
