package io.geph.android.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * @author j3sawyer
 */
public class GephServiceFactory {

    private static final String BASE_URL = "http://localhost:8790";

    private static Retrofit retrofit = build(BASE_URL);

    private static OkHttpClient getClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    private static Retrofit build(String baseUrl) {
        Gson gson = new GsonBuilder()
                .setLenient()
                .create();
        return new Retrofit.Builder()
                .client(getClient())
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
    }

    public static GephService getService() {
        return retrofit.create(GephService.class);
    }

    public static GephService getService(String baseUrl) {
        Retrofit ret = build(baseUrl);
        return ret.create(GephService.class);
    }
}
