package com.example.pakredirect;

import java.util.Map;

public class PakIndexFormatter {
    public static String format(PakIndex index) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, PakEntry> e : index.all().entrySet()) {
            PakEntry p = e.getValue();
            sb.append("✓ ").append(p.name)
              .append("\n  ").append(p.size).append(" bytes\n\n");
            count++;
        }
        if (count == 0) sb.append("未发现 .pak 文件\n\n");
        sb.append("共发现 ").append(count).append(" 个 PAK 文件");
        return sb.toString();
    }
}
