package com.admin;

import com.admin.common.task.OrderExpiry;
import com.admin.controller.ForwardGroupController;
import com.admin.entity.OrderRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.admin.service.impl.OrderServiceImpl;
import com.admin.service.PaymentService;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OrderExpiryTest {
    @Test void closesOnlyExpiredPendingOrdersAndClearsLinks() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE order_record(id INTEGER,status INTEGER,pay_url TEXT,created_time INTEGER,updated_time INTEGER)");
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO order_record VALUES(1,0,'old',?,0),(2,0,'new',?,0),(3,1,'paid',?,0),(4,2,'closed',?,0)",
                    now-OrderExpiry.PAYMENT_WINDOW_MS-1000,now,now-OrderExpiry.PAYMENT_WINDOW_MS-1000,now);
            new OrderExpiry(jdbc).closeExpiredOrders();
            assertEquals(2,jdbc.queryForObject("SELECT status FROM order_record WHERE id=1",Integer.class));
            assertEquals("",jdbc.queryForObject("SELECT pay_url FROM order_record WHERE id=1",String.class));
            assertEquals(0,jdbc.queryForObject("SELECT status FROM order_record WHERE id=2",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT status FROM order_record WHERE id=3",Integer.class));
            var order = new OrderRecord(); order.setCreatedTime(now);
            assertFalse(OrderExpiry.isExpired(order,now+OrderExpiry.PAYMENT_WINDOW_MS-1));
            assertTrue(OrderExpiry.isExpired(order,now+OrderExpiry.PAYMENT_WINDOW_MS));
        } finally { ds.destroy(); }
    }

    @Test void repaymentRejectsClosedExpiredAndOtherUsersOrders() {
        var service = spy(new OrderServiceImpl());
        var expiry = mock(OrderExpiry.class);
        var payment = mock(PaymentService.class);
        ReflectionTestUtils.setField(service,"orderExpiry",expiry);
        ReflectionTestUtils.setField(service,"paymentService",payment);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":1}".getBytes())+".s");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var order = new OrderRecord(); order.setId(1L); order.setUserId(1L);
            order.setStatus(2); order.setCreatedTime(System.currentTimeMillis());
            doReturn(order).when(service).getById(1L);
            assertNotEquals(0,service.repayOrder(1L).getCode());
            order.setStatus(0); order.setCreatedTime(System.currentTimeMillis()-OrderExpiry.PAYMENT_WINDOW_MS);
            assertNotEquals(0,service.repayOrder(1L).getCode());
            order.setCreatedTime(System.currentTimeMillis()); order.setUserId(2L);
            assertNotEquals(0,service.repayOrder(1L).getCode());
            verifyNoInteractions(payment);
            order.setUserId(1L);
            when(payment.createPayment(order)).thenReturn(com.admin.common.lang.R.ok());
            assertEquals(0,service.repayOrder(1L).getCode());
            verify(payment).createPayment(order);
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void administratorOnlySeesOwnGroups() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE forward_group(id INTEGER,user_id INTEGER,name TEXT)");
            jdbc.execute("CREATE TABLE forward(group_id INTEGER)");
            jdbc.update("INSERT INTO forward_group VALUES(1,1,'admin'),(2,2,'private')");
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization","h."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":1,\"role_id\":0}".getBytes())+".s");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var result = new ForwardGroupController(jdbc).list();
            var rows = (List<Map<String,Object>>) result.getData();
            assertEquals(1,rows.size()); assertEquals("admin",rows.get(0).get("name"));
        } finally { RequestContextHolder.resetRequestAttributes(); ds.destroy(); }
    }
}
