package com.example.pakredirect;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal, explicit compatibility surface for the recovered script's TURING OCR calls. */
final class LegacyTuringCompat {
    interface Recognizer {
        String recognize(Request request) throws Exception;
    }

    static final class Request {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int confidenceThreshold;
        final String colorFilter;
        final int projectionMode;
        final int projectionThreshold;
        final String projectionArgument;
        final List<String> dictionaryEntries;

        private Request(int[] region, int confidenceThreshold, String colorFilter,
                        int projectionMode, int projectionThreshold, String projectionArgument,
                        List<String> dictionaryEntries) {
            this.left = region[0];
            this.top = region[1];
            this.right = region[2];
            this.bottom = region[3];
            this.confidenceThreshold = confidenceThreshold;
            this.colorFilter = colorFilter;
            this.projectionMode = projectionMode;
            this.projectionThreshold = projectionThreshold;
            this.projectionArgument = projectionArgument;
            this.dictionaryEntries = Collections.unmodifiableList(new ArrayList<>(dictionaryEntries));
        }
    }

    private final Recognizer recognizer;
    private final int[] region = new int[4];
    private String colorFilter = "";
    private int projectionMode;
    private int projectionThreshold;
    private String projectionArgument = "";
    private List<String> dictionaryEntries = Collections.emptyList();

    private LegacyTuringCompat(Recognizer recognizer) { this.recognizer = recognizer; }

    static boolean acceptsConfidence(double confidence, int thresholdPercent) {
        int threshold = Math.max(0, Math.min(100, thresholdPercent));
        return confidence >= 0d && confidence >= threshold / 100d;
    }

    /** ML Kit rejects source images with either dimension below 32 pixels. */
    static int[] ocrInputDimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("OCR 图片尺寸无效");
        double scale = Math.max(1d, Math.max(32d / width, 32d / height));
        return new int[]{
                Math.max(32, (int) Math.ceil(width * scale)),
                Math.max(32, (int) Math.ceil(height * scale))
        };
    }

    /** Applies TURING's recovered color-pick binary filter to ARGB pixels. */
    static int[] applyColorPickFilter(int[] argb, String specification) {
        if (specification == null || specification.trim().isEmpty()) return argb.clone();
        String value = specification.trim();
        boolean invert = value.startsWith("@");
        if (invert) value = value.substring(1);
        List<int[]> ranges = new ArrayList<>();
        for (String item : value.split("\\|")) {
            String[] parts = item.split("-", -1);
            if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException("TURING 颜色筛选参数无效");
            int color = parseHexColor(parts[0]);
            int tolerance = parts.length == 2 ? parseHexColor(parts[1]) : 0;
            ranges.add(new int[]{(color >>> 16) & 255, (color >>> 8) & 255, color & 255,
                    (tolerance >>> 16) & 255, (tolerance >>> 8) & 255, tolerance & 255});
        }
        int[] output = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int red = (pixel >>> 16) & 255;
            int green = (pixel >>> 8) & 255;
            int blue = pixel & 255;
            boolean matched = false;
            for (int[] range : ranges) {
                if (Math.abs(red - range[0]) <= range[3]
                        && Math.abs(green - range[1]) <= range[4]
                        && Math.abs(blue - range[2]) <= range[5]) {
                    matched = true;
                    break;
                }
            }
            int value8 = matched != invert ? 255 : 0;
            output[i] = 0xff000000 | value8 << 16 | value8 << 8 | value8;
        }
        return output;
    }

    static int[] applyFilter(int[] argb, String specification) {
        if (specification == null) return argb.clone();
        String value = specification.trim();
        if (value.isEmpty() || value.endsWith("-")) return argb.clone();
        if ("auto".equalsIgnoreCase(value) || "ai".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("TURING 的 " + value + " 滤镜尚未移植");
        }
        if (value.startsWith("@") || value.length() >= 6
                && (value.length() > 7 || value.indexOf('-') < 0)) {
            return applyColorPickFilter(argb, value);
        }
        return applyGrayscaleThreshold(argb, value);
    }

    private static int[] applyGrayscaleThreshold(int[] argb, String value) {
        String[] bounds = value.split("-", -1);
        if (bounds.length > 2) throw new IllegalArgumentException("TURING 灰度阈值无效");
        int min = 0;
        int max;
        try {
            if (bounds.length == 1) max = Integer.parseInt(bounds[0]);
            else {
                min = Integer.parseInt(bounds[0]);
                max = Integer.parseInt(bounds[1]);
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("TURING 灰度阈值无效", error);
        }
        if (min < 0 || max > 255 || min > max) throw new IllegalArgumentException("TURING 灰度阈值超出范围");
        int[] output = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int gray = Math.round((((pixel >>> 16) & 255) + ((pixel >>> 8) & 255) + (pixel & 255)) / 3f);
            int value8 = gray >= min && gray <= max ? 0 : 255;
            output[i] = 0xff000000 | value8 << 16 | value8 << 8 | value8;
        }
        return output;
    }

    private static int parseHexColor(String value) {
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        if (normalized.isEmpty() || normalized.length() > 8) throw new IllegalArgumentException("TURING 颜色值无效");
        try { return (int) Long.parseLong(normalized, 16); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("TURING 颜色值无效", error); }
    }

    static LuaTable createApi(Recognizer recognizer) {
        LegacyTuringCompat bridge = new LegacyTuringCompat(recognizer);
        LuaTable api = new LuaTable();
        api.set("来源_获取屏幕像素", bridge.regionFunction());
        api.set("滤镜_彩色_二值化", bridge.filterFunction());
        api.set("切割_范围投影切割", bridge.projectionFunction());
        api.set("字库_加载六十四进制识别字库", bridge.dictionaryFunction());
        api.set("切割_获取切割字符数据", new ZeroArgFunction() {
            @Override public LuaValue call() {
                // ML Kit performs line segmentation internally; no legacy glyph slices exist.
                return new LuaTable();
            }
        });
        api.set("识别", bridge.recognitionFunction());
        return api;
    }

    private VarArgFunction regionFunction() {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int left = args.arg(1).checkint();
                int top = args.arg(2).checkint();
                int right = args.arg(3).checkint();
                int bottom = args.arg(4).checkint();
                if (right <= left || bottom <= top) throw new LuaError("TURING OCR 区域无效");
                region[0] = left;
                region[1] = top;
                region[2] = right;
                region[3] = bottom;
                return LuaValue.NONE;
            }
        };
    }

    private OneArgFunction filterFunction() {
        return new OneArgFunction() {
            @Override public LuaValue call(LuaValue filter) {
                colorFilter = filter.isnil() ? "" : filter.tojstring();
                return LuaValue.NONE;
            }
        };
    }

    private VarArgFunction projectionFunction() {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                projectionMode = args.arg(1).optint(0);
                projectionThreshold = args.arg(2).optint(0);
                projectionArgument = args.arg(3).isnil() ? "" : args.arg(3).tojstring();
                return LuaValue.NONE;
            }
        };
    }

    private OneArgFunction dictionaryFunction() {
        return new OneArgFunction() {
            @Override public LuaValue call(LuaValue data) {
                if (!data.istable()) throw new LuaError("TURING OCR 字库必须为数组");
                List<String> entries = new ArrayList<>();
                LuaTable table = data.checktable();
                for (int index = 1; ; index++) {
                    LuaValue entry = table.get(index);
                    if (entry.isnil()) break;
                    String value = entry.tojstring();
                    if (!value.isEmpty()) entries.add(value);
                }
                dictionaryEntries = entries;
                return LuaValue.valueOf(entries.size());
            }
        };
    }

    private OneArgFunction recognitionFunction() {
        return new OneArgFunction() {
            @Override public LuaValue call(LuaValue threshold) {
                if (region[2] <= region[0] || region[3] <= region[1]) {
                    throw new LuaError("TURING OCR 尚未设置有效识别区域");
                }
                int confidenceThreshold = Math.max(0, Math.min(100, threshold.optint(0)));
                Request request = new Request(region.clone(), confidenceThreshold, colorFilter,
                        projectionMode, projectionThreshold, projectionArgument, dictionaryEntries);
                try {
                    String recognized = recognizer.recognize(request);
                    return LuaValue.valueOf(recognized == null ? "" : recognized.replace("\n", "").replace(" ", ""));
                } catch (Exception error) {
                    throw new LuaError("TURING OCR 替代识别失败：" + error.getMessage());
                }
            }
        };
    }
}
