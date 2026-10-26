package com.admin;

import com.admin.controller.FlowController;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowWriteLockTest {
    @Test void writeLockPrecedesNodeReadAndPayloadProcessing() {
        var controller = new FlowController();
        var jdbc = mock(JdbcTemplate.class);
        var nodes = mock(NodeService.class);
        ReflectionTestUtils.setField(controller,"jdbc",jdbc);
        ReflectionTestUtils.setField(controller,"nodeService",nodes);
        when(nodes.count(any())).thenReturn(1);
        assertEquals("ok",controller.uploadFlowData("[]","test-secret"));
        var order = inOrder(jdbc,nodes);
        order.verify(jdbc).update("UPDATE node SET id=id WHERE secret=?","test-secret");
        order.verify(nodes).count(any());
    }
}
