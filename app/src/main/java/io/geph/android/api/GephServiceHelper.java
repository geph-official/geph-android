package io.geph.android.api;

import android.util.Log;

/**
 * @author j3sawyer
 */
public class GephServiceHelper {
    public static <T, K> void callAsync(final long timeout, final GephServiceTask<T, K> serviceTask,
                                        final SimpleTask<K> task) {
        //noinspection unchecked
        new GephServiceAsyncTask<T, Void, K>() {
            @Override
            protected K doInBackground(T... params) {
                long timestamp = System.currentTimeMillis();

                K ret = null;
                while (System.currentTimeMillis() < timestamp + timeout) {
                    ret = serviceTask.handle(getService(), params);
                    if (ret != null) break;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Thread.currentThread().interrupt();
                    }
                }
                return ret;
            }

            @Override
            protected void onPostExecute(K result) {
                try {
                    task.doTask(result);
                } catch (Exception e) {
                    Log.e("", e.toString());
                    //ignore
                    // TODO (bunsim) why does this fail?
                }
            }
        }.execute();
    }
}
