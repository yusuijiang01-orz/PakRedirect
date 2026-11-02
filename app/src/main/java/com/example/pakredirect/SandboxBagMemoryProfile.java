package com.example.pakredirect;

/** Exact native build IDs whose player/bag layout has been checked for 610+. */
final class SandboxBagMemoryProfile {
    static final String GAME_610_BUILD_ID = "350ee73d222f974988c13b5917337fb2d96fe68a";

    private SandboxBagMemoryProfile() { }

    static boolean isSupported(String buildId) {
        return GAME_610_BUILD_ID.equals(buildId);
    }
}
