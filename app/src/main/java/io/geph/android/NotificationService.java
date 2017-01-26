package io.geph.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

import io.geph.android.api.GephServiceHelper;
import io.geph.android.api.SimpleTask;
import io.geph.android.api.models.NetInfo;
import io.geph.android.api.models.Summary;
import io.geph.android.api.tasks.GetNetInfoTask;
import io.geph.android.api.tasks.GetSummaryTask;

/**
 * @author j3sawyer
 */
@Deprecated
public class NotificationService extends Service {
    //
    private static final String TAG = NotificationService.class.getSimpleName();
    //
    private static final long CACHE_PERIOD_MILLI = 1000 * 10;
    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();
    //
    private NotificationManagerCompat mNM;
    // Unique Identification Number for the Notification.
    // We use it on Notification startUi, and to cancel it.
    private int NOTIFICATION = R.string.local_service_started;
    //
    private Timer mTimer;
    //
    private boolean mInternetDown = false;
    //
    private String mStatus = null;
    //
    private long mConnected = 0;
    //
    private String mAssignedIp = null;
    //
    private SimpleTask<Summary> mOnFetchingNewSummary = new SimpleTask<Summary>() {
        @Override
        public void doTask(Summary summary) {
            String status;
            if (summary != null) {
                status = summary.getStatus();
                status = status.substring(0, 1).toUpperCase() + status.substring(1);
            } else {
                status = "Initializing";
            }

            if (!status.equals(mStatus)) { // ["Initializing", "Connected", "Connecting"]
                showDefaultNotification(status);
                mStatus = status;
            }

            if (status.equalsIgnoreCase("connected")) {
                mConnected = System.currentTimeMillis();
                onConnected(status, summary);
            }
        }
    };
    //
    private GetSummaryTask mSummaryTask = new GetSummaryTask(TAG);
    //
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                //action for connectivity
                if (!ConnectionUtils.isNetworkConnected(context)) {
                    showNotificationWhenDisconnected("Disconnected due to no internet connection");
                    mInternetDown = true;
                    mStatus = null;
                    mAssignedIp = null;
                } else if (mInternetDown) {
                    startTimerForNotification();
                    mInternetDown = false;
                }
            }
        }
    };

    /**
     * @param status
     * @param summary
     */
    private void onConnected(final String status, final Summary summary) {
        GephServiceHelper.callAsync(
                2000,
                new GetNetInfoTask(),
                new SimpleTask<NetInfo>() {
                    @Override
                    public void doTask(NetInfo netInfo) {
                        String ip = (netInfo == null) ? "Assigning a new IP address" : netInfo.getExit();
                        if (mAssignedIp == null
                                || !mAssignedIp.equals(ip)) {
                            mAssignedIp = ip;

                            StringBuilder s = new StringBuilder();
                            s.append(status).append(" as ")
                                    .append(mAssignedIp);
                            showDefaultNotification(s.toString());
                        }
                    }
                }
        );
    }

    @Override
    public void onCreate() {
        mNM = NotificationManagerCompat.from(this);

        startTimerForNotification();

        // Register a broadcast receiver for network connection status changes
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);

        registerReceiver(receiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received startUi id " + startId + ": " + intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Cancel the notification.
        cancelNotification();
        unregisterReceiver(receiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Show a notification while this service is running.
     */
    private void showDefaultNotification(String text) {
        if (mTimer == null) return;

        showNotification(text);
    }

    private void startTimerForNotification() {
        cancelNotification(); // make sure
        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mTimer != null && System.currentTimeMillis() > (mConnected + CACHE_PERIOD_MILLI)) {
                    GephServiceHelper.callAsync(
                            200,
                            mSummaryTask,
                            mOnFetchingNewSummary
                    );
                }
            }
        }, 0, 1000);
    }

    private void cancelNotification() {
        mNM.cancel(NOTIFICATION);
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }

    private void showNotificationWhenDisconnected(String text) {
        cancelNotification();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Bitmap largeIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.ic_launcher);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setTicker(text)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getText(R.string.notification_label))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        Notification notification = builder.build();
        mNM.notify(NOTIFICATION, notification);
    }

    /**
     * @param text
     * @param actions
     */
    private void showNotification(String text, NotificationCompat.Action... actions) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Intent actionIntent = new Intent(this, MainActivity.class);
        actionIntent.setAction(MainActivity.ACTION_STOP_VPN_SERVICE);
        Bitmap largeIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.ic_launcher);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setTicker(text)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getText(R.string.notification_label))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(text))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true);

        for (NotificationCompat.Action action : actions) {
            builder.addAction(action);
        }

        Notification notification = builder.build();
        mNM.notify(NOTIFICATION, notification);
    }

    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
        NotificationService getService() {
            return NotificationService.this;
        }
    }
}
