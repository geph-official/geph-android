package io.geph.android.api.models;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import org.json.JSONObject;

/**
 * @author j3sawyer
 */
public class AccountInfo {
    @SerializedName("FreeBalance")
    private String balance; // in MiB

    @SerializedName("PremiumInfo")
    private JsonObject premiumInfo;

    public String getBalance() {
        return balance;
    }
    public JsonObject getPremiumInfo() { return premiumInfo; }
}