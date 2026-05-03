package io.geph.android.tun2socks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants.F_SETFD
import android.util.Log
import androidx.core.app.NotificationCompat
import com.frybits.harmony.getHarmonySharedPreferences
import com.sun.jna.Library
import com.sun.jna.Native
import io.geph.android.DaemonArgs
import io.geph.android.GephDaemon
import io.geph.android.MainActivity
import io.geph.android.R
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread


interface LibC : Library {
    fun fcntl(fd: Int, cmd: Int, args: Int): Int
    fun dup2(oldFd: Int, newFd: Int): Int
}

class TunnelManager(parentService: TunnelVpnService?) {
    private var parentService: TunnelVpnService? = parentService
    private var tunFd: ParcelFileDescriptor? = null
    private var gephDaemon: GephDaemon? = null

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.i(LOG_TAG, "Intent is null")
            return 0
        }
        Log.i(LOG_TAG, "onStartCommand")

        // Setup and run VPN service with daemon
        setupAndRunVpnService()
        
        // Set up notification
        val ctx = requireContext()
        val notificationIntent = Intent(ctx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(ctx, 0, notificationIntent, FLAG_IMMUTABLE)
        val largeIcon = BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)
        val channelId = createNotificationChannel()
        val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ctx.getText(R.string.notification_label))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        val notification = builder.build()
        
        // Start foreground as special-use on API 34+ (VPNs require continuous FGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            requireVpnService().startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            requireVpnService().startForeground(NOTIFICATION_ID, notification)
        }
        return Service.START_STICKY
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "geph_service"
            val channelName = "Geph background service"
            val chan = NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE)
            chan.description = "Geph background service"
            val notificationManager = requireContext().getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(chan)
            return channelId
        }
        return ""
    }

    private fun setupAndRunVpnService() {
        Log.e("SETUP", "Setting up VPN service and daemon")
        
        // Get DaemonArgs from shared preferences
        val prefs = requireContext().getHarmonySharedPreferences("daemon")
        val daemonArgsJson = prefs.getString(DAEMON_ARGS, null)
        
        if (daemonArgsJson == null) {
            Log.e(LOG_TAG, "No daemon arguments found in preferences")
            parentService?.broadcastVpnStart(false)
            return
        }
        
        // Parse DaemonArgs from JSON
        val daemonArgs = try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(DaemonArgs.serializer(), daemonArgsJson)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse daemon arguments: ${e.message}")
            parentService?.broadcastVpnStart(false)
            return
        }
        
        // Create VPN interface
        var vpnInterface: ParcelFileDescriptor? = null
        while (vpnInterface == null) {
            Log.d("SETUP", "Attempting to create VPN interface")
            val builder = requireParentService().newBuilder()
                .addAddress("100.64.89.64", 10)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("100.64.89.1")
                .addDisallowedApplication(requireContext().packageName)
            
            // Add excluded apps from the app whitelist
            try {
                // Only process app whitelist if it's not empty
                for (packageName in daemonArgs.appWhitelist) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to add app to exclusion list: $packageName")
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error setting up app exclusions: ${e.message}")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            
            vpnInterface = builder.setBlocking(true)
                .setMtu(16384)
                .establish()
        }
        
        tunFd = vpnInterface
        startGephDaemon(vpnInterface, daemonArgs)
    }

    private fun startGephDaemon(vpnInterface: ParcelFileDescriptor, daemonArgs: DaemonArgs) {
        val fd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fd = vpnInterface.detachFd()
            val libc = Native.load(LibC::class.java)
            libc.fcntl(fd, F_SETFD, 0)
            libc.dup2(fd, 0)
            0
        } else {
            -1 // Will handle stdio-based approach for older versions
        }
        // Create a config from the DaemonArgs
        val config = daemonArgs.toConfig(requireContext()).jsonObject

        val controlSockPath = vpnControlSockPath(requireContext())

        // Add VPN-specific configurations to the config
        val vpnEnabledConfig = buildJsonObject {
            // Copy all elements from the original config
            for ((key, value) in config) {
                put(key, value)
            }

            // Add VPN fd configuration if available
            if (fd >= 0) {
                put("vpn_fd", fd)
            }

            // Serve the control RPC over a Unix-domain socket in the app's
            // private files dir, instead of a localhost TCP port that other
            // apps on the device could connect to.
            put("control_listen_unix", controlSockPath)
        }

        // Create and start the daemon
        gephDaemon = GephDaemon(requireContext(), vpnEnabledConfig)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // For older Android versions, manually handle tunnel I/O
            startLegacyIo(vpnInterface)
        }
        
        // Broadcast successful VPN start
        parentService?.broadcastVpnStart(true)
        
        // Monitor daemon for crashes
        thread {
            try {
                val exitCode = gephDaemon?.waitForExit() ?: return@thread
                Log.e(LOG_TAG, "Daemon process exited with code: $exitCode")
                signalStopService()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error monitoring daemon process: ${e.message}")
            }
        }
    }
    
    private fun startLegacyIo(vpnInterface: ParcelFileDescriptor) {
        // download
        Log.e(LOG_TAG, "VPN I/O SET UP")
        Thread {
            val body = ByteArray(40000)
            val writer = FileOutputStream(vpnInterface.fileDescriptor)
            while (!Thread.currentThread().isInterrupted && gephDaemon?.isAlive == true) {
                val n = gephDaemon?.downloadVpn(body) ?: -1
                if (n <= 0) {
                    break
                }
                writer.write(body, 0, n)
            }
            writer.close()
        }.start()
        // upload
        Thread {
            Log.e(LOG_TAG, "VPN I/O UP STARTED")
            val body = ByteArray(40000)
            val reader = FileInputStream(vpnInterface.fileDescriptor)
            while (!Thread.currentThread().isInterrupted && gephDaemon?.isAlive == true) {
                val n = reader.read(body)
                if (n <= 0) {
                    break
                }
                gephDaemon?.uploadVpn(body, n)
            }
            reader.close()
        }.start()
    }

    fun terminateDaemon() {
        gephDaemon?.stopDaemon()
        gephDaemon = null
        
        // Close the VPN interface
        tunFd?.close()
        tunFd = null
    }

    fun onDestroy() {
        terminateDaemon()
        stopForegroundCompat()
    }

    fun signalStopService() {
        parentService?.broadcastVpnDisconnect()
        terminateDaemon()
        stopForegroundCompat()
        parentService?.stopSelf()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val NOTIFICATION_ID = 7839214

        // The key for storing DaemonArgs in SharedPreferences
        const val DAEMON_ARGS = "daemonArgs"

        private const val VPN_CONTROL_SOCK_NAME = "control.sock"
        private const val FALLBACK_CONTROL_SOCK_NAME = "control-fallback.sock"

        /** Filesystem path of the VPN daemon's control RPC Unix socket. */
        @JvmStatic
        fun vpnControlSockPath(context: Context): String =
            File(context.filesDir, VPN_CONTROL_SOCK_NAME).absolutePath

        /** Filesystem path of the fallback (non-VPN) daemon's control RPC Unix socket. */
        @JvmStatic
        fun fallbackControlSockPath(context: Context): String =
            File(context.filesDir, FALLBACK_CONTROL_SOCK_NAME).absolutePath

        private const val LOG_TAG = "TunnelManager"
    }

    private fun requireContext(): Context = checkNotNull(parentService) { "VPN service unavailable" }

    private fun requireParentService(): TunnelVpnService =
        checkNotNull(parentService) { "VPN service unavailable" }

    private fun requireVpnService(): TunnelVpnService =
        checkNotNull(parentService) { "VPN service unavailable" }

    private fun stopForegroundCompat() {
        val service = parentService ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(true)
        }
    }
}
