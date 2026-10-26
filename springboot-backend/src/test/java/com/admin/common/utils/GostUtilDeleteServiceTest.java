package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.alibaba.fastjson.JSONArray;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GostUtilDeleteServiceTest {
    private GostDto result(String message) {
        GostDto dto = new GostDto();
        dto.setMsg(message);
        return dto;
    }

    @Test void mixedExistingAndMissingProtocolsAreFullyDeletedBeforeRecreation() {
        // 模拟 Agent：整批中任一服务缺失时，不删除任何服务。
        for (String existing : List.of("372_1_0_tcp", "372_1_0_udp")) {
            Set<String> registered = new HashSet<>(Set.of(existing));
            List<String> attempted = new ArrayList<>();
            GostDto response = GostUtil.DeleteService(1L,
                    GostUtil.forwardServiceNames("372_1_0", "tcp_udp"), (node, data) -> {
                        assertEquals(1L, node);
                        JSONArray names = data.getJSONArray("services");
                        for (Object name : names) {
                            attempted.add(name.toString());
                            if (!registered.contains(name.toString())) return result("service " + name + " not found");
                        }
                        names.forEach(name -> registered.remove(name.toString()));
                        return result("OK");
                    });
            assertEquals("OK", response.getMsg());
            assertEquals(List.of("372_1_0_tcp", "372_1_0_udp"), attempted);
            assertTrue(registered.isEmpty());
            assertTrue(registered.add("372_1_0_tcp"));
            assertTrue(registered.add("372_1_0_udp"));
        }
    }

    @Test void realErrorsStopDeletionAndAreNotReportedAsSuccess() {
        List<String> attempted = new ArrayList<>();
        GostDto response = GostUtil.DeleteService(1L,
                GostUtil.forwardServiceNames("372_1_0", "tcp_udp"), (node, data) -> {
                    attempted.add(data.getJSONArray("services").getString(0));
                    return result("node not found");
                });
        assertEquals("node not found", response.getMsg());
        assertEquals(List.of("372_1_0_tcp"), attempted);
    }
}
