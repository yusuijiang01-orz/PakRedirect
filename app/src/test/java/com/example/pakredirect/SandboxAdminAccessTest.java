package com.example.pakredirect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SandboxAdminAccessTest {
    private static AuthClient.ProfileResult profile(boolean requestOk, boolean success,
                                                     String role) {
        return new AuthClient.ProfileResult(requestOk, success, "test", true,
                "vip", "2099-01-01T00:00:00Z", role, "");
    }

    @Test public void acceptsOnlyServerConfirmedAdmin() {
        assertTrue(SandboxAdminAccess.isAdminProfile(profile(true, true, "admin")));
        assertTrue(SandboxAdminAccess.isAdminProfile(profile(true, true, " ADMIN ")));
        assertFalse(SandboxAdminAccess.isAdminProfile(profile(true, true, "user")));
        assertFalse(SandboxAdminAccess.isAdminProfile(profile(true, true, "agent")));
        assertFalse(SandboxAdminAccess.isAdminProfile(profile(true, true, "vip")));
        assertFalse(SandboxAdminAccess.isAdminProfile(profile(false, true, "admin")));
        assertFalse(SandboxAdminAccess.isAdminProfile(profile(true, false, "admin")));
        assertFalse(SandboxAdminAccess.isAdminProfile(null));
    }

    @Test public void adminRoleIsNotInferredFromVipOrMissingRole() {
        assertFalse(SandboxAdminAccess.isAdminRole(null));
        assertFalse(SandboxAdminAccess.isAdminRole(""));
        assertFalse(SandboxAdminAccess.isAdminRole("superadmin"));
        assertFalse(SandboxAdminAccess.isAdminRole("user"));
        assertTrue(SandboxAdminAccess.isAdminRole("admin"));
    }
}
