package io.geph.android.tun2socks

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.util.Log

class TunnelVpnService : VpnService() {
    private val binder = LocalBinder()
    private val tunnelManager = TunnelManager(this)

    override fun onBind(intent: Intent): IBinder? {
        val action = intent.action
        return if (action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(LOG_TAG, "on start")
        if (intent?.action == ACTION_STOP_VPN) {
            Log.d(LOG_TAG, "received stop action")
            tunnelManager.signalStopService()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART_ENGINE) {
            Log.d(LOG_TAG, "received restart-engine action")
            // Off the main thread: the restart kills and respawns the engine
            // child. The caller observes completion via the control socket.
            Thread { tunnelManager.restartEngine() }.start()
            return START_NOT_STICKY
        }
        tunnelManager.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(LOG_TAG, "on create")
        TunnelState.getTunnelState().tunnelManager = tunnelManager
    }

    override fun onDestroy() {
        Log.d(LOG_TAG, "on destroy")
        TunnelState.getTunnelState().tunnelManager = null
        tunnelManager.onDestroy()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.e(LOG_TAG, "VPN service revoked.")
        tunnelManager.signalStopService()
    }

    fun newBuilder(): Builder = Builder()

    fun broadcastVpnDisconnect(vararg extras: String) {
        val vpnDisconnect = appBroadcast(TUNNEL_VPN_DISCONNECT_BROADCAST)
        extras.forEach { extra ->
            vpnDisconnect.putExtra(extra, true)
        }
        sendBroadcast(vpnDisconnect)
    }

    fun broadcastVpnStart(success: Boolean) {
        val vpnStart = appBroadcast(TUNNEL_VPN_START_BROADCAST)
        vpnStart.putExtra(TUNNEL_VPN_START_SUCCESS_EXTRA, success)
        sendBroadcast(vpnStart)
    }

    private fun appBroadcast(action: String): Intent = Intent(action).setPackage(packageName)

    inner class LocalBinder : Binder() {
        fun getService(): TunnelVpnService = this@TunnelVpnService
    }

    companion object {
        const val ACTION_STOP_VPN = "io.geph.android.action.STOP_VPN"
        const val ACTION_RESTART_ENGINE = "io.geph.android.action.RESTART_ENGINE"
        const val TUNNEL_VPN_DISCONNECT_BROADCAST = "tunnelVpnDisconnectBroadcast"
        const val TUNNEL_VPN_START_BROADCAST = "tunnelVpnStartBroadcast"
        const val TUNNEL_VPN_START_SUCCESS_EXTRA = "tunnelVpnStartSuccessExtra"
        const val TUNNEL_VPN_STOP_INVALID_CREDENTIAL = "tunnelVpnStopInvalidCredential"
        private const val LOG_TAG = "TunnelVpnService"
    }
}
