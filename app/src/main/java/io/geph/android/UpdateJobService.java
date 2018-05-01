package io.geph.android;

import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Vibrator;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class UpdateJobService extends JobService {
    private static final String TAG = "UpdateJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        final UpdateJobService currService = this;
        final JobParameters jparams = params;
        Log.d(TAG, "JOB STARTED");
        RequestQueue queue = Volley.newRequestQueue(this);
        String url = "https://raw.githubusercontent.com/rensa-labs/geph-autoupdate/master/stable.json";
        JsonObjectRequest stringRequest = new JsonObjectRequest(
                Request.Method.GET,
                url, null, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                try {
                    Context context = getApplicationContext();
                    JSONObject andObj = response.getJSONObject("Android");
                    if (andObj.getString("Latest") != BuildConfig.VERSION_NAME) {
                        // down&install intent
                        Intent diintent = new Intent(context, UpdateService.class);
                        JSONArray mirrs = andObj.getJSONArray("Mirrors");
                        diintent.putExtra("io.geph.android.downURL", mirrs.getString(0));
                        diintent.setAction(mirrs.getString(0));
                        Log.d(TAG, diintent.getStringExtra("io.geph.android.downURL"));
                        PendingIntent diPendingIntent = PendingIntent.getService(context, 0, diintent, 0);
                        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context);
                        mBuilder.setSmallIcon(R.drawable.ic_stat_notification_icon)
                                .setContentTitle(getString(R.string.update_notification))
                                .setContentText(getString(R.string.download_and_install))
                                .setOnlyAlertOnce(true)
                                .setContentIntent(diPendingIntent)
                                .setAutoCancel(true)
                                .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
                                .setPriority(NotificationCompat.PRIORITY_MAX);
                        NotificationManagerCompat notificationManager =
                                NotificationManagerCompat.from(context);
                        notificationManager.notify(12345, mBuilder.build());
                    }
                } catch (JSONException e) {
                    Log.d(TAG, e.toString());
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                // TODO
            }
        });
        queue.add(stringRequest);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
