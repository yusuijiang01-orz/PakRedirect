package com.example.pakredirect;

/** Pure-Java color-keyed image matcher shared by the Lua host and regression tests. */
final class LegacyImageMatcher {
    interface Pixels {
        int width();
        int height();
        int getPixel(int x, int y);
    }

    interface Checkpoint { void check(); }

    static final class Match {
        final int x;
        final int y;
        final double score;

        Match(int x, int y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private LegacyImageMatcher() { }

    static Match find(Pixels screen, Pixels template, int left, int top, int right, int bottom,
                      int mask, double threshold, Checkpoint checkpoint) {
        if (screen == null || template == null) throw new IllegalArgumentException("Images are required");
        if (checkpoint == null) throw new IllegalArgumentException("Checkpoint is required");
        int screenWidth = screen.width(), screenHeight = screen.height();
        int tw = template.width(), th = template.height();
        left = Math.max(0, Math.min(screenWidth, left));
        top = Math.max(0, Math.min(screenHeight, top));
        right = Math.max(0, Math.min(screenWidth, right));
        bottom = Math.max(0, Math.min(screenHeight, bottom));
        threshold = Math.max(0.50d, Math.min(1.0d, threshold));
        if (tw <= 0 || th <= 0 || right - left < tw || bottom - top < th) return null;

        int sampleStep = Math.max(1, Math.min(3, Math.max(tw, th) / 18));
        int templateSamples = countTemplateSamples(template, mask, sampleStep);
        if (templateSamples == 0) return null;

        int maxX = right - tw, maxY = bottom - th;
        long positions = (long) (maxX - left + 1) * (maxY - top + 1);
        int scanStep = (int) Math.max(1, Math.min(8,
                Math.ceil(Math.sqrt((double) positions * templateSamples / 5_000_000d))));
        int bestX = -1, bestY = -1;
        double bestScore = -1d;
        for (int y = top; y <= maxY; y += scanStep) {
            for (int x = left; x <= maxX; x += scanStep) {
                checkpoint.check();
                double score = templateScore(screen, template, x, y, mask, sampleStep);
                if (score > bestScore) { bestX = x; bestY = y; bestScore = score; }
                if (scanStep == 1 && score >= threshold) return new Match(x, y, score);
            }
        }

        // Coarse search is only a candidate generator; verify every pixel nearby.
        if (bestX >= 0) {
            int fromX = Math.max(left, bestX - scanStep + 1);
            int toX = Math.min(maxX, bestX + scanStep - 1);
            int fromY = Math.max(top, bestY - scanStep + 1);
            int toY = Math.min(maxY, bestY + scanStep - 1);
            for (int y = fromY; y <= toY; y++) {
                for (int x = fromX; x <= toX; x++) {
                    checkpoint.check();
                    double score = templateScore(screen, template, x, y, mask, 1);
                    if (score >= threshold) return new Match(x, y, score);
                }
            }
        }
        return null;
    }

    private static int countTemplateSamples(Pixels template, int mask, int step) {
        int count = 0;
        for (int y = 0; y < template.height(); y += step)
            for (int x = 0; x < template.width(); x += step)
                if ((template.getPixel(x, y) & 0x00ffffff) != mask) count++;
        return count;
    }

    private static double templateScore(Pixels screen, Pixels template,
                                        int x, int y, int mask, int step) {
        int hit = 0, count = 0;
        for (int ty = 0; ty < template.height(); ty += step) {
            for (int tx = 0; tx < template.width(); tx += step) {
                int expected = template.getPixel(tx, ty) & 0x00ffffff;
                if (expected == mask) continue;
                int actual = screen.getPixel(x + tx, y + ty) & 0x00ffffff;
                int dr = Math.abs(((expected >> 16) & 255) - ((actual >> 16) & 255));
                int dg = Math.abs(((expected >> 8) & 255) - ((actual >> 8) & 255));
                int db = Math.abs((expected & 255) - (actual & 255));
                if (dr + dg + db <= 42) hit++;
                count++;
            }
        }
        return count == 0 ? 0d : (double) hit / count;
    }
}
