package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * @author j3sawyer
 */
public class AccountInfo {
    @SerializedName("Username")
    private String username;

    @SerializedName("AccID")
    private String accId;

    @SerializedName("Balance")
    private String balance; // in MiB

    public String getUsername() {
        return username;
    }

    public String getAccId() {
        return accId;
    }

    public String getBalance() {
        return balance;
    }
}
