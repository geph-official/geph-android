package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * @author j3sawyer
 */
public class RegisterAccountRequest {
    @SerializedName("Username")
    private String username;

    @SerializedName("PubKey")
    private String pubKey;

    @SerializedName("CaptchaID")
    private String captchaId;

    @SerializedName("CaptchaSoln")
    private String captchaSoln;

    public RegisterAccountRequest(String username, String pubKey, String captchaId, String captchaSoln) {
        this.username = username;
        this.pubKey = pubKey;
        this.captchaId = captchaId;
        this.captchaSoln = captchaSoln;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPubKey() {
        return pubKey;
    }

    public void setPubKey(String pubKey) {
        this.pubKey = pubKey;
    }

    public String getCaptchaId() {
        return captchaId;
    }

    public void setCaptchaId(String captchaId) {
        this.captchaId = captchaId;
    }

    public String getCaptchaSoln() {
        return captchaSoln;
    }

    public void setCaptchaSoln(String captchaSoln) {
        this.captchaSoln = captchaSoln;
    }
}
