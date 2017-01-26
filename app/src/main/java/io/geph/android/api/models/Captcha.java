package io.geph.android.api.models;

import com.google.gson.annotations.SerializedName;

/**
 * @author j3sawyer
 */
public class Captcha {
    @SerializedName("CaptchaID")
    private String captchaId;

    @SerializedName("CaptchaImg")
    private String captchaImg;

    public String getCaptchaId() {
        return captchaId;
    }

    public String getCaptchaImg() {
        return captchaImg;
    }
}
