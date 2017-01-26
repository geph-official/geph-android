package io.geph.android.api;

import android.os.AsyncTask;

/**
 * @author j3sawyer
 */
public abstract class GephServiceAsyncTask<T, L, K> extends AsyncTask<T, L, K> {
    private GephService service;

    public GephServiceAsyncTask() {
        this.service = GephServiceFactory.getService();
    }

    public GephService getService() {
        return service;
    }
}
