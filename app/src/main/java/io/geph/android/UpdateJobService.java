package io.geph.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import java.util.Random;

public class UpdateJobService extends JobService {
    private class Version implements Comparable<Version> {

        private String version;

        public final String get() {
            return this.version;
        }

        public Version(String version) {
            if (version == null)
                throw new IllegalArgumentException("Version can not be null");
            if (!version.matches("[0-9]+(\\.[0-9]+)*"))
                throw new IllegalArgumentException("Invalid version format");
            this.version = version;
        }

        @Override
        public int compareTo(Version that) {
            if (that == null)
                return 1;
            String[] thisParts = this.get().split("\\.");
            String[] thatParts = that.get().split("\\.");
            int length = Math.max(thisParts.length, thatParts.length);
            for (int i = 0; i < length; i++) {
                int thisPart = i < thisParts.length ?
                        Integer.parseInt(thisParts[i]) : 0;
                int thatPart = i < thatParts.length ?
                        Integer.parseInt(thatParts[i]) : 0;
                if (thisPart < thatPart)
                    return -1;
                if (thisPart > thatPart)
                    return 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object that) {
            if (this == that)
                return true;
            if (that == null)
                return false;
            if (this.getClass() != that.getClass())
                return false;
            return this.compareTo((Version) that) == 0;
        }
    }

    private static final String TAG = "UpdateJobService";

    private String createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "geph_update";
            String channelName = "Geph updates";
            NotificationChannel chan = new NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_HIGH);
            chan.setDescription("Geph updates");
            NotificationManager notificationManager = this.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(chan);
            return channelId;
        }
        return "";
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final UpdateJobService currService = this;
        final JobParameters jparams = params;
        Log.d(TAG, "JOB STARTED");
        RequestQueue queue = Volley.newRequestQueue(this);
        String url = "https://gitlab.com/bunsim/geph-autoupdate/raw/master/stable.json";
        if (new Random().nextBoolean()) {
            url = "https://f001.backblazeb2.com/file/geph4-dl/stable.json";
        }
        JsonObjectRequest stringRequest = new JsonObjectRequest(
                Request.Method.GET,
                url, null, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                try {
                    Context context = getApplicationContext();
                    JSONObject andObj = response.getJSONObject("Android");
                    Log.d(TAG, andObj.getString("Latest"));
                    if (new Version(andObj.getString("Latest")).compareTo(new Version(BuildConfig.VERSION_NAME)) > 0) {
                        // down&install intent
                        Intent diintent = new Intent(context, UpdateService.class);
                        JSONArray mirrs = andObj.getJSONArray("Mirrors");
                        diintent.putExtra("io.geph.android.downURL", mirrs.getString(0));
                        diintent.setAction(mirrs.getString(0));
                        Log.d(TAG, diintent.getStringExtra("io.geph.android.downURL"));
                        PendingIntent diPendingIntent = PendingIntent.getService(context, 0, diintent, 0);
                        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, createNotificationChannel());
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
