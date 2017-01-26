package io.geph.android.ui;

import java.util.Map;

/**
 * @author j3sawyer
 */
public class StatusItem {
    public static final int POS_CONN = 0;
    public static final int POS_PUB_IP = 1;
    public static final int POS_UPTIME = 2;
    public static final int POS_REM_BAL = 3;
    public static final int POS_DOWNLOAD = 4;
    public static final int POS_UPLOAD = 5;

    private static final String TITLE_CONN = "Connection Status";
    private static final String TITLE_PUB_IP = "Public IP";
    private static final String TITLE_UPTIME = "Uptime";
    private static final String TITLE_REM_BAL = "Remaining Balance";
    private static final String TITLE_DOWNLOAD = "Download Speed";
    private static final String TITLE_UPLOAD = "Upload Speed";

    private static final String LOADING = "Loading...";
    private static final String CALCULATING = "Calculating...";

    private int pos;
    private String title;
    private String body;
    private boolean initialized;
    private String defaultMessage;

    public StatusItem(int pos, String title, String body, String defaultMessage) {
        this.pos = pos;
        this.title = title;
        this.body = body;
        initialized = body != null;
        this.defaultMessage = defaultMessage;
    }

    public static StatusItem createConn(String connectionStatus) {
        return new StatusItem(POS_CONN, TITLE_CONN, connectionStatus, LOADING);
    }

    public static StatusItem createPubIp(String publicIp) {
        return new StatusItem(POS_PUB_IP, TITLE_PUB_IP, publicIp, LOADING);
    }

    public static StatusItem createUptime(String uptime) {
        return new StatusItem(POS_UPTIME, TITLE_UPTIME, uptime, LOADING);
    }

    public static StatusItem createDownloadSpeed(String downloadSpeed) {
        return new StatusItem(POS_DOWNLOAD, TITLE_DOWNLOAD, downloadSpeed, CALCULATING);
    }

    public static StatusItem createUploadSpeed(String uploadSpeed) {
        return new StatusItem(POS_UPLOAD, TITLE_UPLOAD, uploadSpeed, CALCULATING);
    }

    private static StatusItem createRemainingBalance(String remainingBalance) {
        return new StatusItem(POS_REM_BAL, TITLE_REM_BAL, remainingBalance, LOADING);
    }

    public static Map<Integer, StatusItem> initStatusMap(Map<Integer, StatusItem> map) {
        map.clear();
        map.put(POS_CONN, createConn(null));
        map.put(POS_PUB_IP, createPubIp(null));
        map.put(POS_UPTIME, createUptime(null));
        map.put(POS_REM_BAL, createRemainingBalance(null));
        map.put(POS_DOWNLOAD, createDownloadSpeed(null));
        map.put(POS_UPLOAD, createUploadSpeed(null));
        return map;
    }

    public int getPos() {
        return pos;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        initialized = true;
        this.body = body;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return "StatusItem{" +
                "pos=" + pos +
                ", title='" + title + '\'' +
                ", body='" + body + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        StatusItem that = (StatusItem) o;

        if (pos != that.pos) return false;
        return title.equals(that.title);

    }

    @Override
    public int hashCode() {
        int result = pos;
        result = 31 * result + title.hashCode();
        return result;
    }
}
