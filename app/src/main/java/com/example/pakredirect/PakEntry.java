package com.example.pakredirect;

public class PakEntry {
    public final String name;
    public final long size;
    public final String uri;

    public PakEntry(String name, long size, String uri) {
        this.name = name;
        this.size = size;
        this.uri = uri;
    }
}
