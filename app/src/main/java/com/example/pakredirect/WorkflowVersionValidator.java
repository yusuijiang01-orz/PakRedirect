package com.example.pakredirect;

import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that the signed release label and the version embedded in the
 * decrypted graph describe the same workflow. Node labels and legacy graph IDs
 * are deliberately ignored: they are presentation text, not release metadata.
 */
public final class WorkflowVersionValidator {
    public static final String MINIMUM_SUPPORTED_VERSION = "3.82";
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:^|[^0-9])v?([0-9]+(?:\\.[0-9]+){1,2})(?![0-9])");

    public static final class Info {
        public final String manifestVersion;
        public final String embeddedVersion;
        public final String workflowName;

        Info(String manifestVersion, String embeddedVersion, String workflowName) {
            this.manifestVersion = manifestVersion;
            this.embeddedVersion = embeddedVersion;
            this.workflowName = workflowName;
        }

        public String summary() {
            return "v" + embeddedVersion + "（签名清单与解密内容一致）";
        }
    }

    private WorkflowVersionValidator() { }

    public static Info validate(String signedReleaseVersion, JSONObject workflow) {
        if (workflow == null) throw new IllegalStateException("解密后的工作流 JSON 为空，已阻止启动");
        String workflowName = workflow.optString("name", "").trim();
        String embeddedSource = firstNonEmpty(
                workflow.optString("ryluxVersion", ""),
                workflow.optString("releaseVersion", ""),
                workflow.optString("workflowVersion", ""),
                workflow.optString("workflow_version", ""));
        if (embeddedSource.isEmpty()) {
            JSONObject metadata = workflow.optJSONObject("metadata");
            if (metadata == null) metadata = workflow.optJSONObject("meta");
            if (metadata != null) {
                embeddedSource = firstNonEmpty(
                        metadata.optString("ryluxVersion", ""),
                        metadata.optString("releaseVersion", ""),
                        metadata.optString("workflowVersion", ""),
                        metadata.optString("workflow_version", ""));
            }
        }
        // Current CoraFlow exports encode their human release in the graph name.
        // Do not use root "version" (editor schema), graph "id", or node names.
        if (embeddedSource.isEmpty()) embeddedSource = workflowName;

        String signed = extract(signedReleaseVersion);
        String embedded = extract(embeddedSource);
        if (signed.isEmpty()) {
            throw new IllegalStateException("签名工作流清单没有可识别的发布版本，已阻止启动");
        }
        if (embedded.isEmpty()) {
            throw new IllegalStateException("已解密工作流未声明可识别的发布版本（工作流名称："
                    + (workflowName.isEmpty() ? "未命名" : workflowName) + "），已阻止启动");
        }
        if (compare(embedded, signed) != 0) {
            throw new IllegalStateException("工作流版本不一致：签名清单声明 v" + signed
                    + "，但解密后的工作流内容为 v" + embedded + "（" + workflowName
                    + "）。已阻止启动，避免运行旧脚本；请重新导出并加密发布同一版本。");
        }
        if (compare(embedded, MINIMUM_SUPPORTED_VERSION) < 0) {
            throw new IllegalStateException("工作流版本过旧：当前解密内容为 v" + embedded
                    + "，RYLUX 当前要求至少 v" + MINIMUM_SUPPORTED_VERSION + "。已阻止启动。");
        }
        return new Info(signed, embedded, workflowName);
    }

    static String extract(String value) {
        if (value == null) return "";
        Matcher matcher = VERSION.matcher(value.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? parse(a[i]) : 0;
            int bv = i < b.length ? parse(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int parse(String value) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException invalid) { return 0; }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
