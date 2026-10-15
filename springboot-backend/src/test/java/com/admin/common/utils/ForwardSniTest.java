package com.admin.common.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardSniTest {
    @Test void validatesNamesAndAllowedRanges() {
        assertEquals("app.example.com", ForwardSni.normalize("APP.example.com.,app.example.com", false));
        assertTrue(ForwardSni.allows("example.com", "app.example.com"));
        assertTrue(ForwardSni.allows("example.com", "example.com"));
        assertFalse(ForwardSni.allows(".example.com", "example.com"));
        assertFalse(ForwardSni.allows("example.com", "otherexample.com"));
        assertThrows(IllegalArgumentException.class, () -> ForwardSni.normalize("127.0.0.1", false));
        assertThrows(IllegalArgumentException.class, () -> ForwardSni.normalize("*.example.com", false));
    }
}
