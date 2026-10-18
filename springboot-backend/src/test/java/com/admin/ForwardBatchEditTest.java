package com.admin;
import com.admin.controller.ForwardBatchEditController;
import com.admin.service.ForwardService;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ForwardBatchEditTest {
 @Test void validatesBeforeUpdatingAndPreservesAdvancedFields() {
  var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
  try {
   var jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE forward(id INTEGER,user_id INTEGER,tunnel_id INTEGER,strategy TEXT,tls_inbound_id INTEGER)");jdbc.execute("CREATE TABLE forward_port(forward_id INTEGER,port INTEGER)");
   jdbc.update("INSERT INTO forward VALUES(1,1,9,'round',0),(2,2,8,'fifo',0)");jdbc.update("INSERT INTO forward_port VALUES(1,9001),(2,9002)");
   var req=new MockHttpServletRequest();req.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes())+".s");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
   var service=mock(ForwardService.class);var c=new ForwardBatchEditController(jdbc,service);
   assertNotEquals(0,c.edit(Map.of("ids",List.of(1,2),"text","example.com:80|new|9001")).getCode());
   assertNotEquals(0,c.edit(Map.of("ids",List.of(1,2),"text","example.com:80|new|9001\nbad|other|9002")).getCode());verifyNoInteractions(service);
   when(service.updateForward(any())).thenAnswer(inv->{ForwardUpdateDto dto=inv.getArgument(0);assertNull(dto.getProtocol());assertNull(dto.getOriginTls());assertNull(dto.getTlsInboundId());assertNull(dto.getGroupId());return dto.getId()==1?R.ok():R.err("端口冲突");});
   var result=c.edit(Map.of("ids",List.of(1,2),"text","example.com:443|new|25060\n[2001:db8::1]:80|other|25061"));
   assertEquals(0,result.getCode());var data=(Map<?,?>)result.getData();assertEquals(1,data.get("count"));assertEquals(1,((List<?>)data.get("failures")).size());verify(service,times(2)).updateForward(any());
   clearInvocations(service);req.removeHeader("Authorization");req.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":1}".getBytes())+".s");
   assertNotEquals(0,c.edit(Map.of("ids",List.of(1,2),"text","a.com:80|a|9001\nb.com:80|b|9002")).getCode());verifyNoInteractions(service);
  } finally {RequestContextHolder.resetRequestAttributes();ds.destroy();}
 }
}
