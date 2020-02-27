package io.geph.android.tun2socks;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.VpnService;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.geph.android.AccountUtils;
import io.geph.android.MainActivity;
import io.geph.android.R;

public class TunnelManager implements Tunnel.HostService {

    public static final int NOTIFICATION_ID = 7839214;

    public static final String SOCKS_SERVER_ADDRESS_BASE = "socksServerAddress";
    public static final String SOCKS_SERVER_PORT_EXTRA = "socksServerPort";
    public static final String DNS_SERVER_PORT_EXTRA = "dnsServerPort";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String EXIT_NAME = "exitName";
    public static final String EXIT_KEY = "exitKey";
    public static final String USE_TCP = "useTCP";
    public static final String FORCE_BRIDGES = "forceBridges";
    public static final String BYPASS_CHINA = "bypassChina";

    private static final String LOG_TAG = "TunnelManager";
    private static final String CACHE_DIR_NAME = "geph";
    private static final String DAEMON_IN_NATIVELIB_DIR = "libgeph.so";
    private TunnelVpnService m_parentService = null;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private AtomicBoolean m_isStopping;
    private Tunnel m_tunnel = null;
    private String mSocksServerAddressBase;
    private String mSocksServerAddress;
    private String mSocksServerPort;
    private String mDnsServerPort;
    private String mDnsResolverAddress;
    private String mUsername;
    private String mPassword;
    private String mExitName;
    private String mExitKey;
    private Boolean mUseTCP;
    private Boolean mForceBridges;
    private Boolean mBypassChina;
    private Process mSocksProxyDaemonProc;
    private AtomicBoolean m_isReconnecting;

    public TunnelManager(TunnelVpnService parentService) {
        m_parentService = parentService;
        m_isStopping = new AtomicBoolean(false);
        m_isReconnecting = new AtomicBoolean(false);
        m_tunnel = Tunnel.newTunnel(this);
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.i(LOG_TAG, "labooyah");
            return 0;
        }
        Log.i(LOG_TAG, "onStartCommand");

        mSocksServerAddressBase = intent.getStringExtra(SOCKS_SERVER_ADDRESS_BASE);
        mSocksServerPort = intent.getStringExtra(SOCKS_SERVER_PORT_EXTRA);
        mSocksServerAddress = mSocksServerAddressBase + ":" + mSocksServerPort;
        mDnsServerPort = intent.getStringExtra(DNS_SERVER_PORT_EXTRA);
        mDnsResolverAddress = mSocksServerAddressBase + ":" + mDnsServerPort;
        Log.i(LOG_TAG, "onStartCommand parsed some stuff");
        mUsername = intent.getStringExtra(USERNAME);
        mPassword = intent.getStringExtra(PASSWORD);
        mExitKey = intent.getStringExtra(EXIT_KEY);
        mExitName = intent.getStringExtra(EXIT_NAME);
        mForceBridges = intent.getBooleanExtra(FORCE_BRIDGES, false);
        mUseTCP = intent.getBooleanExtra(USE_TCP, false);
        mBypassChina = intent.getBooleanExtra(BYPASS_CHINA, false);
        Log.i(LOG_TAG, "onStartCommand parsed intent");

        if (setupAndRunSocksProxyDaemon() == null) {
            Log.e(LOG_TAG, "Failed to start the socks proxy daemon.");
            m_parentService.broadcastVpnStart(false /* success */);
            return 0;
        }

        if (mSocksServerAddress == null) {
            Log.e(LOG_TAG, "Failed to receive the socks server address.");
            m_parentService.broadcastVpnStart(false /* success */);
            return 0;
        }

        try {
            if (!m_tunnel.startRouting()) {
                Log.e(LOG_TAG, "Failed to establish VPN");
                m_parentService.broadcastVpnStart(false /* success */);
            } else {
                startTunnel();
            }
        } catch (Tunnel.Exception e) {
            Log.e(LOG_TAG, String.format("Failed to establish VPN: %s", e.getMessage()));
            m_parentService.broadcastVpnStart(false /* success */);
        }

        final Context ctx = getContext();

        Intent notificationIntent = new Intent(ctx, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 0, notificationIntent, 0);

        Bitmap largeIcon = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher);
        String channelId = createNotificationChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, createNotificationChannel())
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ctx.getText(R.string.notification_label))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        Notification notification = builder.build();

        // starting this service on foreground to avoid accidental GC by Android system
        getVpnService().startForeground(NOTIFICATION_ID, notification);

        return Service.START_STICKY;
    }

    private String createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "geph_service";
            String channelName = "Geph background service";
            NotificationChannel chan = new NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setDescription("Geph background service");
            NotificationManager notificationManager = getContext().getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(chan);
            return channelId;
        }
        return "";
    }

    private Process setupAndRunSocksProxyDaemon() {
        // Run Geph-Go with uProxy style fail-safe
        mSocksProxyDaemonProc = runSocksProxyDaemon();
        if (mSocksProxyDaemonProc == null) {
            return mSocksProxyDaemonProc;
        }

        Thread socksProxyDaemonErr = new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = "Geph";
                InputStream err = mSocksProxyDaemonProc.getErrorStream();
                Scanner scanner = new Scanner(err);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    Log.e(tag, line);
                    if (line.contains("FATAL")) {
                        Log.e(tag, "Stopping the service due to invalid credential");
                        m_parentService.broadcastVpnDisconnect(TunnelVpnService.TUNNEL_VPN_STOP_INVALID_CREDENTIAL);
                        break;
                    }
                }
                Log.e(tag, "stopping log stuff because the process died");
                getVpnService().stopForeground(true);
                System.exit(0);
            }
        });
        socksProxyDaemonErr.start();

        return mSocksProxyDaemonProc;
    }

    private Process runSocksProxyDaemon() {
        final Context ctx = getContext();
        final String daemonBinaryPath =
                ctx.getApplicationInfo().nativeLibraryDir + "/" + DAEMON_IN_NATIVELIB_DIR;

        try {
            List<String> commands = new ArrayList<>();
            commands.add(daemonBinaryPath);
            commands.add("-username");
            commands.add(mUsername);
            commands.add("-password");
            commands.add(mPassword);
            commands.add("-exitName");
            commands.add(mExitName);
            commands.add("-exitKey");
            commands.add(mExitKey);
            commands.add("-fakeDNS=true");
            commands.add("-dnsAddr=127.0.0.1:49983");
            if (mUseTCP) {
                commands.add("-useTCP");
            }
            if (mForceBridges) {
                commands.add("-forceBridges");
            }
            if (mBypassChina) {
                commands.add("-bypassChina");
            }
            Log.i(LOG_TAG, commands.toString());
            ProcessBuilder pb = new ProcessBuilder(commands);

            return pb.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void terminateSocksProxyDaemon() {
        if (mSocksProxyDaemonProc != null) {
            mSocksProxyDaemonProc.destroy();
            mSocksProxyDaemonProc = null;
        }
    }

    public void restartSocksProxyDaemon() {
        terminateSocksProxyDaemon();
        setupAndRunSocksProxyDaemon();
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy() {
        terminateSocksProxyDaemon();

        if (m_tunnelThread == null) {
            return;
        }

        // signalStopService should have been called, but in case is was not, call here.
        // If signalStopService was not already called, the join may block the calling
        // thread for some time.
        signalStopService();

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;

        // stop the foreground service
        getVpnService().stopForeground(true);
    }

    // Signals the runTunnel thread to stopUi. The thread will self-stopUi the service.
    // This is the preferred method for stopping the tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }
    }

    // Stops the tunnel thread and restarts it with |socksServerAddress|.
    public void restartTunnel(final String socksServerAddress, String socksServerPort, String dnsServerPort) {
        Log.i(LOG_TAG, "Restarting tunnel.");
        if (socksServerAddress == null ||
                socksServerAddress.equals(mSocksServerAddress)) {
            // Don't reconnect if the socks server address hasn't changed.
            m_parentService.broadcastVpnStart(true /* success */);
            return;
        }
        mSocksServerAddress = socksServerAddress;
        mSocksServerPort = socksServerPort;
        mDnsServerPort = dnsServerPort;

        m_isReconnecting.set(true);

        // Signaling stopUi to the tunnel thread with the reconnect flag set causes
        // the thread to stopUi the tunnel (but not the VPN or the service) and send
        // the new SOCKS server address to the DNS resolver before exiting itself.
        // When the DNS broadcasts its local address, the tunnel will restart.
        signalStopService();
    }

    private void startTunnel() {
        m_tunnelThreadStopSignal = new CountDownLatch(1);
        m_tunnelThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                runTunnel(mSocksServerAddress, mDnsResolverAddress);
                            }
                        });
        m_tunnelThread.start();
    }

    private void runTunnel(String socksServerAddress, String dnsResolverAddress) {
        m_isStopping.set(false);

        try {
            if (!m_tunnel.startTunneling(socksServerAddress, dnsResolverAddress)) {
                throw new Tunnel.Exception("application is not prepared or revoked");
            }
            Log.i(LOG_TAG, "VPN service running");
            m_parentService.broadcastVpnStart(true /* success */);

            try {
                m_tunnelThreadStopSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            m_isStopping.set(true);

        } catch (Tunnel.Exception e) {
            Log.e(LOG_TAG, String.format("Start tunnel failed: %s", e.getMessage()));
            m_parentService.broadcastVpnStart(false /* success */);
        } finally {
            if (m_isReconnecting.get()) {
                // Stop tunneling only, not VPN, if reconnecting.
                Log.i(LOG_TAG, "Stopping tunnel.");
                m_tunnel.stopTunneling();
            } else {
                // Stop VPN tunnel and service only if not reconnecting.
                Log.i(LOG_TAG, "Stopping VPN and tunnel.");
                m_tunnel.stop();
                m_parentService.stopForeground(true);
                m_parentService.stopSelf();
            }
            m_isReconnecting.set(false);
        }
    }

    //----------------------------------------------------------------------------
    // Tunnel.HostService
    //----------------------------------------------------------------------------

    @Override
    public String getAppName() {
        return "Tun2Socks";
    }

    @Override
    public Context getContext() {
        return m_parentService;
    }

    @Override
    public VpnService getVpnService() {
        return ((TunnelVpnService) m_parentService);
    }

    @Override
    public VpnService.Builder newVpnServiceBuilder() {
        return ((TunnelVpnService) m_parentService).newBuilder();
    }

    @Override
    public void onDiagnosticMessage(String message) {
        Log.d(LOG_TAG, message);
    }

    @Override
    public void onTunnelConnected() {
        Log.i(LOG_TAG, "Tunnel connected.");
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onVpnEstablished() {
        Log.i(LOG_TAG, "VPN established.");
    }
}