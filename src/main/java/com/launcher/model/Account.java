package com.launcher.model;

public class Account {
    private String username;
    private String uuid;
    private String accessToken;
    private String userType;
    private String refreshToken;
    private long accessTokenExpiresAt;
    private boolean autoCreated;

    public Account(String username, String uuid, String accessToken, String userType) {
        this(username, uuid, accessToken, userType, null, 0L);
    }

    public Account(String username, String uuid, String accessToken, String userType,
                   String refreshToken, long accessTokenExpiresAt) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.userType = userType;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }
    public String getUserType() { return userType; }
    public String getRefreshToken() { return refreshToken; }
    public long getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public boolean isAutoCreated() { return autoCreated; }
    public void setAutoCreated(boolean value) { autoCreated = value; }
    public void setAccessToken(String value) { accessToken = value; }
    public void setRefreshToken(String value) { refreshToken = value; }
    public void setAccessTokenExpiresAt(long value) { accessTokenExpiresAt = value; }

    @Override
    public String toString() {
        return username + " (" + ("msa".equalsIgnoreCase(userType) ? "Microsoft" : "Offline") + ")";
    }
}
