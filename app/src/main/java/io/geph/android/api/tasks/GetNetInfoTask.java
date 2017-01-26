package io.geph.android.api.tasks;

import android.util.Log;

import java.io.IOException;

import io.geph.android.api.GephService;
import io.geph.android.api.GephServiceTask;
import io.geph.android.api.models.NetInfo;
import retrofit2.Call;

/**
 * @author j3sawyer
 */
public class GetNetInfoTask implements GephServiceTask<Object, NetInfo> {
    private static final String TAG = GetNetInfoTask.class.getSimpleName();

    @Override
    public NetInfo handle(GephService service, Object... params) {
        Call<NetInfo> call = service.getNetInfo();
        try {
            return call.execute().body();
        } catch (IOException e) {
            Log.e(TAG, "Failed to call /netinfo: " + e.getMessage());
        }
        return null;
    }
}
