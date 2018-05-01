package io.geph.android;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public class UpdateService extends IntentService {
    private static final String TAG = "UpdateBroadcastReceiver";

    public UpdateService() {
        super("UpdateService");
    }

    @Override
    public void onHandleIntent(Intent intent) {
        String downURL = intent.getStringExtra("io.geph.android.downURL");
        Log.d(TAG, "update intent received: " + Uri.parse(downURL));
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downURL));
        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(browserIntent);
    }
}
