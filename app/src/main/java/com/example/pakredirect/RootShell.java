package com.example.pakredirect;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class RootShell {
    private RootShell() {}

    public static Result run(String command) {
        StringBuilder sb = new StringBuilder();
        int code = -1;
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            code = p.waitFor();
        } catch (Throwable t) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return new Result(code, sb.toString().trim());
    }

    public static final class Result {
        public final int code;
        public final String output;
        public Result(int code, String output) { this.code = code; this.output = output; }
        public boolean ok() { return code == 0; }
        @Override public String toString() { return "exit=" + code + (output.isEmpty() ? "" : "\n" + output); }
    }
}
