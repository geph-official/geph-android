package io.geph.android.api.tasks;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import io.geph.android.api.GephService;
import io.geph.android.api.GephServiceTask;
import io.geph.android.api.models.Summary;
import retrofit2.Call;

/**
 * @author j3sawyer
 */
public class GetSummaryTask implements GephServiceTask<Object, Summary> {
    private static final AtomicInteger ID_GEN = new AtomicInteger();
    private String mCaller;
    private int mTaskId;

    public GetSummaryTask() {
        this("");
    }

    public GetSummaryTask(String caller) {
        mTaskId = ID_GEN.incrementAndGet();
        if (caller.isEmpty())
            caller = "caller" + mTaskId;
        mCaller = caller;
    }

    @Override
    public Summary handle(GephService service, Object... params) {
        Call<Summary> call = service.getSummary();
        try {
            return call.execute().body();
        } catch (IOException e) {
            Log.e(mCaller, "Failed to call /summary: " + e.getMessage());
            return null;
        }
    }
}
