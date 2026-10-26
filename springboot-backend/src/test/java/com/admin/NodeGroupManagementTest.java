package com.admin;
import com.admin.controller.NodeGroupController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeGroupManagementTest {
    private void login(int user,int role) {
        var req=new MockHttpServletRequest();
        req.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":"+user+",\"role_id\":"+role+"}").getBytes())+".s");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }
    @Test void emptyGroupsBatchPermissionsRemovalAndLegacyNames() {
        var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE node(id INTEGER PRIMARY KEY,owner_user_id INTEGER,group_name TEXT NOT NULL DEFAULT '',probe_mode INTEGER NOT NULL DEFAULT 0)");
            jdbc.execute("CREATE TABLE node_group(user_id INTEGER,name TEXT,PRIMARY KEY(user_id,name))");
            jdbc.update("INSERT INTO node(id,owner_user_id,group_name) VALUES(1,2,'Japan'),(2,2,''),(3,3,'Japan'),(4,NULL,'')");
            var c=new NodeGroupController(jdbc);
            login(2,1);
            assertEquals(0,c.list().getCode());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM node_group WHERE user_id=2 AND name='Japan'",Integer.class));
            assertEquals(0,c.create(Map.of("name","Empty")).getCode());
            assertNotEquals(0,c.create(Map.of("name","Empty")).getCode());
            assertNotEquals(0,c.assign(Map.of("ids",List.of(1,3),"name","Empty")).getCode());
            assertEquals("Japan",jdbc.queryForObject("SELECT group_name FROM node WHERE id=1",String.class));
            assertEquals(0,c.assign(Map.of("ids",List.of(1,2),"name","Empty")).getCode());
            assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE group_name='Empty'",Integer.class));
            assertEquals(0,c.assign(Map.of("ids",List.of(2),"name","")).getCode());
            assertEquals(0,c.delete(Map.of("name","Empty")).getCode());
            assertEquals("",jdbc.queryForObject("SELECT group_name FROM node WHERE id=1",String.class));
            assertEquals("Japan",jdbc.queryForObject("SELECT group_name FROM node WHERE id=3",String.class));
            assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM node",Integer.class));
            jdbc.update("INSERT INTO node(id,owner_user_id,group_name,probe_mode) VALUES(5,2,'Private probe',1)");
            assertNotEquals(0,c.assign(Map.of("ids",List.of(5),"name","")).getCode());
            login(1,0);
            c.list();
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM node_group WHERE name='Private probe'",Integer.class));
            assertNotEquals(0,c.assign(Map.of("ids",List.of(5),"name","Japan")).getCode());
            assertEquals(0,c.assign(Map.of("ids",List.of(1,3,4),"name","Japan")).getCode());
            assertEquals(0,c.delete(Map.of("name","Japan")).getCode());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM node WHERE group_name<>'' AND probe_mode=0",Integer.class));
        } finally {RequestContextHolder.resetRequestAttributes();ds.destroy();}
    }
}
