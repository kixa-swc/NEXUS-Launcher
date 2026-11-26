package com.launcher.model;

public class GameVersion {
    private String id;
    private String type;
    private String url;

    public GameVersion(String id, String type, String url) {
        this.id = id;
        this.type = type;
        this.url = url;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getUrl() { return url; }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}