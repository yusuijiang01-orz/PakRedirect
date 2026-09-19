package com.example.pakredirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Public-resource routing for networks where GitHub is slow or intermittently
 * unavailable. Only public repository/API URLs are sent through the proxy/CDN;
 * authentication tokens are never attached to these requests.
 *
 * Every caller must still validate the downloaded bytes with its authoritative
 * SHA-256/size/signature metadata before accepting them.
 */
public final class CnDownloadRouter {
    private static final String OWNER_REPO = "yusuijiang01-orz/PakRedirect";
    private static final String BRANCH = "main";
    private static final String GH_PROXY = "https://gh-proxy.com/";

    private CnDownloadRouter() {}

    public static String accelerated(String githubUrl) {
        if (githubUrl == null || githubUrl.trim().isEmpty()) return githubUrl;
        String value = githubUrl.trim();
        if (value.startsWith(GH_PROXY)) return value;
        return GH_PROXY + value;
    }

    public static String[] githubApiUrls(String directApiUrl) {
        return unique(accelerated(directApiUrl), directApiUrl);
    }

    public static String[] publicRepoFileUrls(String path) {
        return publicRepoFileUrls(
                "https://raw.githubusercontent.com/" + OWNER_REPO + "/" + BRANCH + "/pak/",
                path
        );
    }

    /** Build mirrors from the directory selected by the authorized manifest. */
    public static String[] publicRepoFileUrls(String baseUrl, String fileName) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (!base.endsWith("/")) base += "/";
        String safeName = normalizeRepoPath(fileName);
        String raw = base + safeName;
        String jsdelivr = jsdelivrUrl(base, safeName);
        return unique(accelerated(raw), jsdelivr, raw);
    }

    public static String[] largeGithubFileUrls(String directUrl) {
        return unique(accelerated(directUrl), directUrl);
    }

    private static String normalizeRepoPath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.contains("..")) throw new IllegalArgumentException("Invalid repository path");
        return value;
    }

    private static String jsdelivrUrl(String rawBase, String fileName) {
        String prefix = "https://raw.githubusercontent.com/" + OWNER_REPO + "/";
        if (!rawBase.startsWith(prefix)) return "";
        String relativeBase = rawBase.substring(prefix.length());
        return "https://cdn.jsdelivr.net/gh/" + OWNER_REPO + "@" + relativeBase + fileName;
    }

    private static String[] unique(String... values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.trim().isEmpty()) continue;
                String normalized = value.trim();
                if (!out.contains(normalized)) out.add(normalized);
            }
        }
        return out.toArray(new String[0]);
    }
}
