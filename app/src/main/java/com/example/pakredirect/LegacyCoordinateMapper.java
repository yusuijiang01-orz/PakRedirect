package com.example.pakredirect;

/** Maps recovered-script coordinates to the captured display, including rotation. */
final class LegacyCoordinateMapper {
    private final int referenceWidth;
    private final int referenceHeight;
    private final int screenWidth;
    private final int screenHeight;
    private final double scaleX;
    private final double scaleY;

    LegacyCoordinateMapper(int configuredWidth, int configuredHeight, int screenWidth, int screenHeight) {
        if (configuredWidth <= 0 || configuredHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("Display dimensions must be positive");
        }
        boolean screenLandscape = screenWidth >= screenHeight;
        boolean configLandscape = configuredWidth >= configuredHeight;
        this.referenceWidth = screenLandscape == configLandscape ? configuredWidth : configuredHeight;
        this.referenceHeight = screenLandscape == configLandscape ? configuredHeight : configuredWidth;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.scaleX = (double) screenWidth / referenceWidth;
        this.scaleY = (double) screenHeight / referenceHeight;
    }

    int toScreenX(double x) { return (int) Math.round(x * scaleX); }
    int toScreenY(double y) { return (int) Math.round(y * scaleY); }
    int toReferenceX(double x) { return (int) Math.round(x / scaleX); }
    int toReferenceY(double y) { return (int) Math.round(y / scaleY); }
    int toScreenDeltaX(double x) { return (int) Math.round(x * scaleX); }
    int toScreenDeltaY(double y) { return (int) Math.round(y * scaleY); }
    int referenceWidth() { return referenceWidth; }
    int referenceHeight() { return referenceHeight; }
    double scaleX() { return scaleX; }
    double scaleY() { return scaleY; }
    boolean shouldScaleTemplate(boolean capturedFromCurrentDisplay) {
        return !capturedFromCurrentDisplay
                && (Math.abs(scaleX - 1d) > 0.001d || Math.abs(scaleY - 1d) > 0.001d);
    }

    int left(double x) { return clamp((int) Math.floor(x * scaleX), screenWidth); }
    int top(double y) { return clamp((int) Math.floor(y * scaleY), screenHeight); }
    int right(double x) { return clamp((int) Math.ceil(x * scaleX), screenWidth); }
    int bottom(double y) { return clamp((int) Math.ceil(y * scaleY), screenHeight); }
    int edgeRight(double x) { return Math.max(0, Math.min(screenWidth, (int) Math.ceil(x * scaleX))); }
    int edgeBottom(double y) { return Math.max(0, Math.min(screenHeight, (int) Math.ceil(y * scaleY))); }

    String mapColorPoints(String encoded) {
        if (encoded == null || encoded.isEmpty()) return encoded;
        String[] points = encoded.split(",", -1);
        StringBuilder mapped = new StringBuilder();
        for (String point : points) {
            if (mapped.length() > 0) mapped.append(',');
            String[] fields = point.split("\\|", 3);
            if (fields.length != 3) { mapped.append(point); continue; }
            try {
                mapped.append(toScreenX(Double.parseDouble(fields[0].trim()))).append('|')
                        .append(toScreenY(Double.parseDouble(fields[1].trim()))).append('|')
                        .append(fields[2]);
            } catch (NumberFormatException invalidPoint) { mapped.append(point); }
        }
        return mapped.toString();
    }

    private static int clamp(int value, int size) { return Math.max(0, Math.min(size - 1, value)); }
}
