package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * @author j3sawyer
 */
public class DeriveKeys {
    @SerializedName("PubKey")
    private String pubKey;

    @SerializedName("PrivKey")
    private String privKey;

    @SerializedName("UID")
    private String uid;

    public String getPubKey() {
        return pubKey;
    }

    public String getPrivKey() {
        return privKey;
    }

    public String getUid() {
        return uid;
    }
}
