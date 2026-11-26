package com.launcher.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Catalog {
    private Catalog() { }

    public static List<ModrinthClient.Project> searchProjects(String type, String query, String loader, String mc, int limit) throws IOException {
        return ModrinthClient.searchProjects(type, query, loader, mc, limit);
    }

    public static List<ModrinthClient.Project> projects(List<String> ids) throws IOException {
        List<String> clean = new ArrayList<>();
        for (String id : ids) if (id != null && !id.isBlank() && !id.startsWith("cf:")) clean.add(id);
        return ModrinthClient.projects(clean);
    }

    public static List<ModrinthClient.Release> listReleases(String projectId, String loader, String mc, String... extensions) throws IOException {
        return ModrinthClient.listReleases(projectId, loader, mc, extensions);
    }

    public static ModrinthClient.Release latestRelease(String projectId, String loader, String mc, String... extensions) throws IOException {
        return ModrinthClient.latestRelease(projectId, loader, mc, extensions);
    }

    public static ModrinthClient.Release releaseById(String versionId, String... extensions) throws IOException {
        return ModrinthClient.releaseById(versionId, extensions);
    }
}
