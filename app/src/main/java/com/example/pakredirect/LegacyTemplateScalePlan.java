package com.example.pakredirect;

/** Candidate raster scales for game UI templates inside a fitted game viewport. */
final class LegacyTemplateScalePlan {
    private LegacyTemplateScalePlan() { }

    static double[] candidates(double scaleX, double scaleY) {
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || scaleX <= 0d || scaleY <= 0d) {
            return new double[]{1d};
        }
        // Game UI is fitted to the limiting screen axis. Recovered templates
        // were also captured at two legacy raster ratios (1.05 / 1.25), so
        // include the measured 84% correction around the fitted scale and a
        // small overscan candidate for screenshot resampling.
        double fittedScale = Math.min(scaleX, scaleY);
        double[] proposed = {fittedScale, fittedScale * 0.84d, fittedScale * 1.05d};
        double[] unique = new double[proposed.length];
        int count = 0;
        for (double value : proposed) {
            if (!Double.isFinite(value) || value < 0.35d || value > 3d) continue;
            boolean duplicate = false;
            for (int index = 0; index < count; index++) {
                if (Math.abs(unique[index] - value) < 0.005d) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique[count++] = value;
        }
        if (count == 0) return new double[]{1d};
        double[] result = new double[count];
        System.arraycopy(unique, 0, result, 0, count);
        return result;
    }
}
