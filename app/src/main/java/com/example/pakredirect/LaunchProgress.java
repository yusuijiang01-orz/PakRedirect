package com.example.pakredirect;

/**
 * Process-local launch/update status shared between MainActivity and InterceptService.
 * The service and activity run in the same application process.
 */
public final class LaunchProgress {
    private static volatile boolean starting;
    private static volatile boolean running;
    private static volatile int progress = -1;
    private static volatile String message = "";
    private static volatile String error = "";

    private LaunchProgress() {}

    public static void begin(String text) {
        starting = true;
        running = false;
        progress = -1;
        message = text == null ? "" : text;
        error = "";
    }

    public static void update(String text, int value) {
        message = text == null ? "" : text;
        progress = value < 0 ? -1 : Math.max(0, Math.min(100, value));
    }

    public static void ready(String text) {
        message = text == null ? "" : text;
        progress = 100;
        running = true;
        starting = false;
        error = "";
    }

    public static void fail(String text) {
        error = text == null ? "启动失败" : text;
        message = error;
        progress = -1;
        running = false;
        starting = false;
    }

    public static void stopped(String text) {
        message = text == null ? "" : text;
        progress = -1;
        running = false;
        starting = false;
    }

    public static boolean isStarting() { return starting; }
    public static boolean isRunning() { return running; }
    public static int progress() { return progress; }
    public static String message() { return message; }
    public static String error() { return error; }
}
