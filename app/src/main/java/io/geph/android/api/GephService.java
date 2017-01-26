package io.geph.android.api;

import io.geph.android.api.models.AccountInfo;
import io.geph.android.api.models.Captcha;
import io.geph.android.api.models.DeriveKeys;
import io.geph.android.api.models.NetInfo;
import io.geph.android.api.models.RegisterAccountRequest;
import io.geph.android.api.models.Summary;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * @author j3sawyer
 */
public interface GephService {
    @GET("/summary")
    Call<Summary> getSummary();

    @GET("/netinfo")
    Call<NetInfo> getNetInfo();

    @POST("/fresh-captcha")
    Call<Captcha> getFreshCaptcha();

    @GET("/derive-keys")
    Call<DeriveKeys> deriveKeys(@Query("uname") String uname, @Query("pwd") String pwd);

    @POST("/register-account")
    Call<Object> registerAccount(@Body RegisterAccountRequest request);

    @GET("/accinfo")
    Call<AccountInfo> getAccountInfo();
}
