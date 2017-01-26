package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * @author j3sawyer
 */
public class NetInfo {
    @SerializedName("Exit")
    private String exit;

    @SerializedName("Entry")
    private String entry;

    @SerializedName("Protocol")
    private String protocol;

    @SerializedName("ActiveTuns")
    private List<String> activeTunnels;

    public String getExit() {
        return exit;
    }

    public String getEntry() {
        return entry;
    }

    public String getProtocol() {
        return protocol;
    }

    public List<String> getActiveTunnels() {
        return activeTunnels;
    }
}
