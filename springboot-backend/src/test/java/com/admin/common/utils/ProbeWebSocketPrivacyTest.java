package com.admin.common.utils;

import com.admin.entity.Node;
import com.admin.entity.UserNodePermission;
import com.admin.service.UserNodePermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProbeWebSocketPrivacyTest {
    @Test void privateProbeOnlyReachesOwnerEvenIfOtherUserIsAdminOrAuthorized() {
        var server = new WebSocketServer();
        var permissions = mock(UserNodePermissionService.class);
        when(permissions.getUserNodePermissionMap(22)).thenReturn(Map.of(9,new UserNodePermission()));
        ReflectionTestUtils.setField(server,"userNodePermissionService",permissions);
        Node node = new Node(); node.setId(9L); node.setOwnerUserId(11); node.setProbeMode(1);
        assertTrue(server.canReceiveNode(browser(11,1),node));
        assertFalse(server.canReceiveNode(browser(22,0),node));
        assertFalse(server.canReceiveNode(browser(22,1),node));
        var agent = mock(WebSocketSession.class);
        when(agent.getAttributes()).thenReturn(Map.of("type","1","id",11L));
        assertFalse(server.canReceiveNode(agent,node));
        verifyNoInteractions(permissions);
        node.setProbeMode(0);
        assertTrue(server.canReceiveNode(browser(22,0),node));
        assertTrue(server.canReceiveNode(browser(22,1),node));
        when(permissions.getUserNodePermissionMap(33)).thenReturn(Map.of());
        assertFalse(server.canReceiveNode(browser(33,1),node));
    }

    private WebSocketSession browser(int user, int role) {
        var session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of("type","0","id",(long)user,"roleId",role));
        return session;
    }
}
