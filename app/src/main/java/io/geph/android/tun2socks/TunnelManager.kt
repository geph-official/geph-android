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
import io.geph.android.ReviewPromptState
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
    // How the engine reaches the tun: 0 = inherits the app's fd 0 (API 26+,
    // dup2'd once at first start and never closed), -1 = legacy stdio pump.
    private var engineFd = -1
    private var reviewSessionRecorded = false

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
        engineFd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fd = vpnInterface.detachFd()
            val libc = Native.load(LibC::class.java)
            libc.fcntl(fd, F_SETFD, 0)
            libc.dup2(fd, 0)
            0
        } else {
            -1 // Will handle stdio-based approach for older versions
        }

        spawnEngine(daemonArgs)

        if (!reviewSessionRecorded) {
            ReviewPromptState.recordVpnSessionStarted(requireContext())
            reviewSessionRecorded = true
        }

        // Broadcast successful VPN start
        parentService?.broadcastVpnStart(true)
    }

    /**
     * Spawn a geph5-client engine against the already-configured tun (the
     * app's fd 0 on API 26+, the stdio pump otherwise). Used both for the
     * first start and for in-place restarts.
     */
    private fun spawnEngine(daemonArgs: DaemonArgs) {
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
            if (engineFd >= 0) {
                put("vpn_fd", engineFd)
            }

            // Serve the control RPC over a Unix-domain socket in the app's
            // private files dir, instead of a localhost TCP port that other
            // apps on the device could connect to.
            put("control_listen_unix", controlSockPath)
        }

        // Create and start the daemon
        val daemon = GephDaemon(requireContext(), vpnEnabledConfig)
        gephDaemon = daemon

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // For older Android versions, manually handle tunnel I/O
            startLegacyIo(tunFd!!, daemon)
        }

        // Monitor the engine for crashes. Only tear the service down if the
        // engine that exited is still the current one — an engine we replaced
        // in restartEngine() exits deliberately.
        thread {
            try {
                val exitCode = daemon.waitForExit()
                if (gephDaemon === daemon) {
                    Log.e(LOG_TAG, "Daemon process exited with code: $exitCode")
                    signalStopService()
                } else {
                    Log.i(LOG_TAG, "old engine exited with code $exitCode after restart")
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error monitoring daemon process: ${e.message}")
            }
        }
    }

    /**
     * Swap the engine under the live tun: kill the current geph5-client and
     * spawn a fresh one from the (re-read) persisted DaemonArgs. The
     * VpnService and tun stay up the whole time, so traffic blackholes rather
     * than leaks during the gap. Used to apply a new exit while connected.
     */
    @Synchronized
    fun restartEngine() {
        val old = gephDaemon ?: run {
            Log.w(LOG_TAG, "restartEngine with no running engine; ignoring")
            return
        }

        val prefs = requireContext().getHarmonySharedPreferences("daemon")
        val daemonArgsJson = prefs.getString(DAEMON_ARGS, null)
        if (daemonArgsJson == null) {
            Log.e(LOG_TAG, "restartEngine: no daemon arguments in preferences")
            return
        }
        val daemonArgs = try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(DaemonArgs.serializer(), daemonArgsJson)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "restartEngine: failed to parse daemon arguments: ${e.message}")
            return
        }

        // Detach first so the old engine's watchdog sees it is no longer
        // current and does not tear the service down.
        gephDaemon = null
        old.stopDaemon()
        spawnEngine(daemonArgs)
    }

    private fun startLegacyIo(vpnInterface: ParcelFileDescriptor, daemon: GephDaemon) {
        // The pumps capture the engine instance they serve (not the field), so
        // threads belonging to a replaced engine die with it instead of
        // fighting the new engine's pumps. The streams are never closed here:
        // closing them would close the shared tun fd, whose lifetime belongs
        // to tunFd (closed in terminateDaemon).
        // download
        Log.e(LOG_TAG, "VPN I/O SET UP")
        Thread {
            val body = ByteArray(40000)
            val writer = FileOutputStream(vpnInterface.fileDescriptor)
            try {
                while (!Thread.currentThread().isInterrupted && daemon.isAlive) {
                    val n = daemon.downloadVpn(body)
                    if (n <= 0) {
                        break
                    }
                    writer.write(body, 0, n)
                }
            } catch (e: Exception) {
                Log.d(LOG_TAG, "VPN download pump exited: ${e.message}")
            }
        }.start()
        // upload
        Thread {
            Log.e(LOG_TAG, "VPN I/O UP STARTED")
            val body = ByteArray(40000)
            val reader = FileInputStream(vpnInterface.fileDescriptor)
            try {
                while (!Thread.currentThread().isInterrupted && daemon.isAlive) {
                    val n = reader.read(body)
                    if (n <= 0) {
                        break
                    }
                    daemon.uploadVpn(body, n)
                }
            } catch (e: Exception) {
                Log.d(LOG_TAG, "VPN upload pump exited: ${e.message}")
            }
        }.start()
    }

    fun terminateDaemon() {
        parentService?.let { ReviewPromptState.recordVpnSessionEnded(it) }
        reviewSessionRecorded = false
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
        terminateDaemon()
        parentService?.broadcastVpnDisconnect()
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
