package com.example.pakredirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes RYLUX-owned downloads to the public Gitee mirror. Unrelated external
 * URLs remain on their configured origin and never receive authentication data.
 *
 * Every caller must still validate the downloaded bytes with its authoritative
 * SHA-256/size/signature metadata before accepting them.
 */
public final class CnDownloadRouter {
    private static final String OWNER_REPO = "yusuijiang01-orz/PakRedirect";
    private static final String BRANCH = "main";
    private static final String GITEE_REPO = "perish-with-honor/rlyux";
    private static final String GITEE_RAW_BASE =
            "https://gitee.com/" + GITEE_REPO + "/raw/master/";
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

    /** Use Gitee for files mirrored from this repository; keep unrelated URLs unchanged. */
    public static String[] acceleratedCandidates(String githubUrl) {
        if (githubUrl == null || githubUrl.trim().isEmpty()) return new String[0];
        String value = githubUrl.trim();
        String giteeUrl = mapOwnedGithubUrl(value);
        if (giteeUrl != null) return new String[]{giteeUrl};
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

    /** Rewrites repository-owned patch links embedded in the game's linkspak. */
    public static String giteeHostedDownload(String url) {
        if (url == null) return null;
        String mapped = mapOwnedGithubUrl(url.trim());
        return mapped == null ? url : mapped;
    }

    public static String[] githubApiUrls(String directApiUrl) {
        return acceleratedCandidates(directApiUrl);
    }

    public static String[] publicRepoFileUrls(String path) {
        String safePath = normalizeRepoPath(path);
        if (safePath.startsWith("pak/")) return new String[]{GITEE_RAW_BASE + safePath};
        return new String[]{GITEE_RAW_BASE + "pak/" + safePath};
    }

    /** Build mirrors from the directory selected by the authorized manifest. */
    public static String[] publicRepoFileUrls(String baseUrl, String fileName) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (!base.endsWith("/")) base += "/";
        String safeName = normalizeRepoPath(fileName);
        String raw = base + safeName;
        String giteeUrl = mapOwnedGithubUrl(raw);
        if (giteeUrl != null) return new String[]{giteeUrl};
        String jsdelivr = jsdelivrUrl(base, safeName);
        return unique(CF_WORKER + raw, GH_PROXY + raw, jsdelivr, raw);
    }

    public static String[] largeGithubFileUrls(String directUrl) {
        return acceleratedCandidates(directUrl);
    }

    /** Protected PAKs are Gitee Release assets; Gitee Raw rejects large files. */
    public static String[] protectedPakFileUrls(String fileName, String githubUrl) {
        String safeName = normalizeRepoPath(fileName);
        if (safeName.contains("/")) throw new IllegalArgumentException("Invalid protected file name");
        if (safeName.toLowerCase().endsWith(".rpe")) {
            String tag = githubUrl != null && githubUrl.contains("/pak-test/") ? "pak-test" : "pak-latest";
            return new String[]{"https://gitee.com/" + GITEE_REPO + "/releases/download/" + tag + "/" + safeName};
        }
        // linkspak.txt is part of the same authorized content bundle as the
        // encrypted PAKs. Preserve the manifest-selected channel (pak or
        // pak-test); always routing it to production makes beta manifests fail
        // their SHA-256 check before the sandbox can launch.
        String mapped = mapOwnedGithubUrl(githubUrl == null ? "" : githubUrl.trim());
        return new String[]{mapped == null ? githubUrl : mapped};
    }

    private static String mapOwnedGithubUrl(String value) {
        String rawPrefix = "https://raw.githubusercontent.com/" + OWNER_REPO + "/" + BRANCH + "/";
        if (value.startsWith(rawPrefix)) {
            String path = normalizeRepoPath(value.substring(rawPrefix.length()));
            if (path.startsWith("pak-test/") && path.toLowerCase().endsWith(".rpe")) {
                String name = path.substring("pak-test/".length());
                if (!name.contains("/")) return "https://gitee.com/" + GITEE_REPO + "/releases/download/pak-test/" + name;
            }
            return GITEE_RAW_BASE + path;
        }
        String releasesPrefix = "https://github.com/" + OWNER_REPO + "/releases/download/";
        if (value.startsWith(releasesPrefix)) {
            String path = value.substring(releasesPrefix.length());
            int slash = path.indexOf('/');
            if (slash > 0 && slash < path.length() - 1) {
                String tag = path.substring(0, slash);
                String asset = path.substring(slash + 1);
                if (("pak-latest".equals(tag) || "pak-test".equals(tag))
                        && !tag.contains("..") && !asset.contains("..")) {
                    return "https://gitee.com/" + GITEE_REPO + "/releases/download/" + tag + "/" + asset;
                }
            }
        }
        return null;
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
