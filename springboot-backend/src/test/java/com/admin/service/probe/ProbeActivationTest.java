package com.admin.service.probe;

import com.admin.common.dto.GostDto;
import com.admin.service.UserNodePermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProbeActivationTest {
    @Test void activationRequiresOwnerAndReadyAndCanRetry() {
        var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE node(id INTEGER,owner_user_id INTEGER,probe_mode INTEGER,status INTEGER,version TEXT,name TEXT,port TEXT,updated_time INTEGER)");
            jdbc.update("INSERT INTO node VALUES(1,11,1,1,'2.0.17-beta','probe','',0)");
            var permissions=mock(UserNodePermissionService.class);
            var service=spy(new ProbeActivationService(jdbc,new DataSourceTransactionManager(ds),permissions));
            assertNotEquals(0,service.activate(1,22,"node","10000-20000").getCode());
            verify(service,never()).send(anyLong(),anyString());
            GostDto failed=new GostDto(); failed.setMsg("连接中断");
            doReturn(failed).when(service).send(1,"EnableForwarding");
            assertNotEquals(0,service.activate(1,11,"node","10000-20000").getCode());
            assertEquals(1,jdbc.queryForObject("SELECT probe_mode FROM node",Integer.class));
            GostDto ok=new GostDto(); ok.setMsg("OK");
            GostDto ready=new GostDto(); ready.setMsg("OK"); ready.setData(Map.of("ready",true));
            doReturn(ok).when(service).send(1,"EnableForwarding");
            doReturn(failed).when(service).send(1,"ForwardingStatus");
            assertNotEquals(0,service.activate(1,11,"node","10000-20000").getCode());
            assertEquals(1,jdbc.queryForObject("SELECT probe_mode FROM node",Integer.class));
            doReturn(ready).when(service).send(1,"ForwardingStatus");
            assertEquals(0,service.activate(1,11,"node","10000-20000").getCode());
            assertEquals(0,jdbc.queryForObject("SELECT probe_mode FROM node",Integer.class));
            verify(permissions).grantNodeOwnerPermissions(11,1L);
            assertEquals(0,service.activate(1,11,"node","10000-20000").getCode());
            verify(service,times(3)).send(1,"EnableForwarding");
            assertFalse(ProbeActivationService.validPorts("0-100"));
            assertFalse(ProbeActivationService.validPorts("100-99"));
            assertFalse(ProbeActivationService.validPorts("80,"));
            assertFalse(ProbeActivationService.supportsActivation("2.0.16-beta"));
        } finally {ds.destroy();}
    }
}
