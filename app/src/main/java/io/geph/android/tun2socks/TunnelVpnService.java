package io.geph.android.tun2socks;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TunnelVpnService extends VpnService {

    public static final String TUNNEL_VPN_DISCONNECT_BROADCAST =
            "tunnelVpnDisconnectBroadcast";
    public static final String TUNNEL_VPN_START_BROADCAST =
            "tunnelVpnStartBroadcast";
    public static final String TUNNEL_VPN_START_SUCCESS_EXTRA =
            "tunnelVpnStartSuccessExtra";
    public static final String TUNNEL_VPN_STOP_INVALID_CREDENTIAL =
            "tunnelVpnStopInvalidCredential";
    private static final String LOG_TAG = "TunnelVpnService";
    private final IBinder m_binder = new LocalBinder();
    private TunnelManager m_tunnelManager = new TunnelManager(this);

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals(SERVICE_INTERFACE)) {
            return super.onBind(intent);
        }
        return m_binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_TAG, "on start");
        m_tunnelManager.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        Log.d(LOG_TAG, "on create");
        TunnelState.getTunnelState().setTunnelManager(m_tunnelManager);
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "on destroy");
        TunnelState.getTunnelState().setTunnelManager(null);
        m_tunnelManager.onDestroy();
    }

    @Override
    public void onRevoke() {
        Log.e(LOG_TAG, "VPN service revoked.");
        broadcastVpnDisconnect();
        // stopSelf will trigger onDestroy in the main thread.
        stopSelf();
    }

    public VpnService.Builder newBuilder() {
        return new VpnService.Builder();
    }

    // Broadcast non-user-initiated VPN disconnect.
    public void broadcastVpnDisconnect(String... extras) {
        Intent vpnDisconnect = new Intent(TUNNEL_VPN_DISCONNECT_BROADCAST);

        for (String extra : extras) {
            vpnDisconnect.putExtra(extra, true);
        }

        dispatchBroadcast(vpnDisconnect);
    }

    // Broadcast VPN start. |success| is true if the VPN and tunnel were started
    // successfully, and false otherwise.
    public void broadcastVpnStart(boolean success) {
        Intent vpnStart = new Intent(TUNNEL_VPN_START_BROADCAST);
        vpnStart.putExtra(TUNNEL_VPN_START_SUCCESS_EXTRA, success);
        dispatchBroadcast(vpnStart);
    }

    private void dispatchBroadcast(final Intent broadcast) {
        LocalBroadcastManager.getInstance(TunnelVpnService.this)
                .sendBroadcast(broadcast);
    }

    public class LocalBinder extends Binder {
        public TunnelVpnService getService() {
            return TunnelVpnService.this;
        }
    }
}