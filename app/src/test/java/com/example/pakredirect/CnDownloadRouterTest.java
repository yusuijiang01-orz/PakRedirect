package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CnDownloadRouterTest {
    @Test
    public void protectedLinkspakUsesManifestSelectedProductionDirectory() {
        String url = "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/linkspak.txt";

        assertEquals(
                "https://gitee.com/perish-with-honor/rlyux/raw/master/pak/linkspak.txt",
                CnDownloadRouter.protectedPakFileUrls("linkspak.txt", url)[0]
        );
    }

    @Test
    public void protectedLinkspakUsesManifestSelectedTestDirectory() {
        String url = "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak-test/linkspak.txt?sha256=abc123";

        assertEquals(
                "https://gitee.com/perish-with-honor/rlyux/raw/master/pak-test/linkspak.txt?sha256=abc123",
                CnDownloadRouter.protectedPakFileUrls("linkspak.txt", url)[0]
        );
    }

    @Test
    public void protectedEncryptedPakKeepsMatchingReleaseChannel() {
        String url = "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak-test/settings.pak.rpe";

        assertEquals(
                "https://gitee.com/perish-with-honor/rlyux/releases/download/pak-test/settings.pak.rpe",
                CnDownloadRouter.protectedPakFileUrls("settings.pak.rpe", url)[0]
        );
    }
}
