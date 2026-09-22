package com.example.pakredirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Public-resource routing for networks where GitHub is slow or intermittently
 * unavailable. Only public repository/API URLs are sent through the proxy/CDN;
 * authentication tokens are never attached to these requests.
 *
 * Download candidate order: 自建 Cloudflare Worker 反代 → 公共 gh-proxy → 原始直连。
 * 小文件（≤20MB）额外保留 jsDelivr 作为补充；大文件 jsDelivr 会失败，自动落到下一候选。
 *
 * Every caller must still validate the downloaded bytes with its authoritative
 * SHA-256/size/signature metadata before accepting them.
 */
public final class CnDownloadRouter {
    private static final String OWNER_REPO = "yusuijiang01-orz/PakRedirect";
    private static final String BRANCH = "main";

    /**
     * 自建 Cloudflare Worker 反代。
     * 部署 worker.js 后把下面的占位符替换为真实地址，例如
     * https://rylux-cdn.你的子域名.workers.dev/
     */
    private static final String CF_WORKER = "https://rylux-cdn.335399288.workers.dev/";
    /** 公共加速，作为自建 Worker 的兜底。 */
    private static final String GH_PROXY = "https://gh-proxy.com/";

    private static final String[] GITHUB_HOSTS = {
            "raw.githubusercontent.com",
            "media.githubusercontent.com",
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
    };

    private CnDownloadRouter() {}

    /** 加速候选列表：CF Worker → gh-proxy → 原始直连；非 GitHub 域名原样返回。 */
    public static String[] acceleratedCandidates(String githubUrl) {
        if (githubUrl == null || githubUrl.trim().isEmpty()) return new String[0];
        String value = githubUrl.trim();
        if (value.startsWith(CF_WORKER) || value.startsWith(GH_PROXY)) {
            return new String[]{value};
        }
        if (!isGithubUrl(value)) return new String[]{value};
        return unique(CF_WORKER + value, GH_PROXY + value, value);
    }

    public static String accelerated(String githubUrl) {
        String[] candidates = acceleratedCandidates(githubUrl);
        return candidates.length > 0 ? candidates[0] : githubUrl;
    }

    public static String[] githubApiUrls(String directApiUrl) {
        return acceleratedCandidates(directApiUrl);
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
        return unique(CF_WORKER + raw, GH_PROXY + raw, jsdelivr, raw);
    }

    public static String[] largeGithubFileUrls(String directUrl) {
        return acceleratedCandidates(directUrl);
    }

    private static boolean isGithubUrl(String value) {
        String lower = value.toLowerCase();
        for (String host : GITHUB_HOSTS) {
            if (lower.startsWith("https://" + host) || lower.startsWith("http://" + host)) {
                return true;
            }
        }
        return false;
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
