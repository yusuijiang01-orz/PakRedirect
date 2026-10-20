package com.example.pakredirect;

import java.util.List;

/** Recognizes only the game's known first-use terms page from OCR line centers. */
final class ScriptStartupTerms {
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
