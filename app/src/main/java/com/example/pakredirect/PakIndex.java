package com.example.pakredirect;

import java.util.LinkedHashMap;
import java.util.Map;

public class PakIndex {
    private final Map<String, PakEntry> entries = new LinkedHashMap<>();

    public void clear() { entries.clear(); }

    public void put(PakEntry entry) {
        entries.put(entry.name.toLowerCase(), entry);
    }

    public Map<String, PakEntry> all() {
        return entries;
    }
}
