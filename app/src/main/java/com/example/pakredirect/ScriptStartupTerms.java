package com.example.pakredirect;

import java.util.List;

/** Coordinates and anchors for the game's known first-use terms page. */
final class ScriptStartupTerms {
    private static final double[] AGREE_TEMPLATE_SCALE_FACTORS =
            {0.82d, 0.88d, 0.94d, 1.00d, 1.06d, 1.12d, 1.18d};
    private static final float TITLE_LEFT = 0.35f;
    private static final float TITLE_RIGHT = 0.65f;
    private static final float TITLE_TOP = 0.08f;
    private static final float TITLE_BOTTOM = 0.20f;

    private ScriptStartupTerms() { }

    static final class Line {
        final String text;
        final int x;
        final int y;

        Line(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    static final class Tap {
        final int x;
        final int y;

        Tap(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Small central crop containing the terms dialog's fixed title. */
    static int[] titleRegion(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{0, 0, 0, 0};
        return new int[]{Math.round(width * TITLE_LEFT), Math.round(height * TITLE_TOP),
                Math.round(width * TITLE_RIGHT), Math.round(height * TITLE_BOTTOM)};
    }

    static int[] dialogRegion(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{0, 0, 0, 0};
        return new int[]{Math.round(width * 0.12f), Math.round(height * 0.08f),
                Math.round(width * 0.88f), Math.round(height * 0.94f)};
    }

    static boolean isTermsTitle(String text) {
        return compact(text).contains("使用条款");
    }

    static Tap findAgreeTap(List<Line> lines, int width, int height) {
        if (lines == null || width <= 0 || height <= 0) return null;
        StringBuilder pageText = new StringBuilder();
        Line agree = null;
        Line exit = null;
        for (Line line : lines) {
            if (line == null) continue;
            String value = compact(line.text);
            if (line.y < height * 0.70f) pageText.append(value);
            if ("同意".equals(value) && inButtonRegion(line, width, height, true)) {
                if (agree != null) return null;
                agree = line;
            }
            if ("退出".equals(value) && inButtonRegion(line, width, height, false)) {
                if (exit != null) return null;
                exit = line;
            }
        }
        String body = pageText.toString();
        if (agree == null || exit == null || Math.abs(agree.y - exit.y) > height * 0.08f
                || !body.contains("规则和使用条款")
                || !body.contains("参与游戏时")
                || !body.contains("服务协议")
                || !body.contains("已默认接受以下规定")) return null;
        return new Tap(agree.x, agree.y);
    }

    /** Converts a matched button template's top-left into its screen-space center. */
    static Tap templateCenter(int left, int top, int templateWidth, int templateHeight) {
        if (templateWidth <= 0 || templateHeight <= 0) return null;
        return new Tap(left + templateWidth / 2, top + templateHeight / 2);
    }

    /** Search box measured from the localized 1280x720 terms-page capture. */
    static int[] agreeSearchRegion(int width, int height) {
        if (width <= 0 || height <= 0) return new int[]{0, 0, 0, 0};
        return new int[]{Math.round(width * 380f / 1280f), Math.round(height * 550f / 720f),
                Math.round(width * 580f / 1280f), Math.round(height * 650f / 720f)};
    }

    /** Candidate template scales around the measured 1600x900 sandbox capture. */
    static double[] agreeTemplateScales(int width, int height) {
        if (width <= 0 || height <= 0) return new double[0];
        double base = 1.05d * Math.min(width / 1600d, height / 900d);
        double[] scales = new double[AGREE_TEMPLATE_SCALE_FACTORS.length];
        for (int i = 0; i < scales.length; i++) scales[i] = base * AGREE_TEMPLATE_SCALE_FACTORS[i];
        return scales;
    }

    static boolean isLocalizedLoginPage(List<Line> lines) {
        if (lines == null) return false;
        StringBuilder pageText = new StringBuilder();
        for (Line line : lines) {
            if (line != null) pageText.append(compact(line.text));
        }
        String text = pageText.toString();
        return text.contains("忘记密码") && text.contains("注册账号") && text.contains("登录");
    }

    private static boolean inButtonRegion(Line line, int width, int height, boolean left) {
        float x = (float) line.x / width;
        float y = (float) line.y / height;
        return y >= 0.70f && y <= 0.95f
                && x >= (left ? 0.25f : 0.50f)
                && x <= (left ? 0.50f : 0.75f);
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
