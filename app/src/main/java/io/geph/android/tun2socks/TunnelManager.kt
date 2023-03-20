package io.geph.android.tun2socks

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import android.system.Os
import android.system.OsConstants.F_SETFD
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import io.geph.android.MainActivity
import io.geph.android.R
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess


interface LibC : Library { // A representation of libC in Java
    fun fcntl(fd: Int, cmd: Int, args: Int): Int; // mapping of the puts function, in C `int puts(const char *s);
    fun dup2(oldFd: Int, newFd: Int): Int;
}

class TunnelManager(parentService: TunnelVpnService?) {
    private var m_parentService: TunnelVpnService? = null
    private var m_tunnelThreadStopSignal: CountDownLatch? = null
    private var m_tunnelThread: Thread? = null
    private val m_isStopping: AtomicBoolean
    private var mSocksServerAddressBase: String? = null
    private var mSocksServerAddress: String? = null
    private var mSocksServerPort: String? = null
    private var mDnsServerPort: String? = null
    private var mDnsResolverAddress: String? = null
    private var mUsername: String? = null
    private var mPassword: String? = null
    private var mExitName: String? = null
    private var mListenAll: Boolean? = null
    private var mForceBridges: Boolean? = null
    private var mBypassChinese: Boolean? = null
    private var mForceProtocol: String? = null
    private var mExcludeAppsJson: String? = null
    private var mDaemonProc: Process? = null
    private val m_isReconnecting: AtomicBoolean
    private var tunFd: ParcelFileDescriptor? = null

    // Implementation of android.app.Service.onStartCommand
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.i(LOG_TAG, "labooyah")
            return 0
        }
        Log.i(LOG_TAG, "onStartCommand")


        val prefs = context!!.getSharedPreferences("daemon", Context.MODE_PRIVATE);
        mSocksServerAddressBase = prefs.getString(SOCKS_SERVER_ADDRESS_BASE, "")
        mSocksServerPort = prefs.getString(SOCKS_SERVER_PORT_EXTRA, "")
        mSocksServerAddress = "$mSocksServerAddressBase:$mSocksServerPort"
        mDnsServerPort = prefs.getString(DNS_SERVER_PORT_EXTRA, "")
        mDnsResolverAddress = "$mSocksServerAddressBase:$mDnsServerPort"
        Log.i(LOG_TAG, "onStartCommand parsed some stuff")
        mUsername = prefs.getString(USERNAME, "")
        mPassword = prefs.getString(PASSWORD, "")
        mExitName = prefs.getString(EXIT_NAME, "")
        mForceBridges = prefs.getBoolean(FORCE_BRIDGES, false)
        mListenAll = prefs.getBoolean(LISTEN_ALL, false)
        mBypassChinese = prefs.getBoolean(BYPASS_CHINESE, false)
        mForceProtocol = prefs.getString(FORCE_PROTOCOL, "")
        mExcludeAppsJson = prefs.getString(EXCLUDE_APPS_JSON, "")
        Log.i(LOG_TAG, "onStartCommand parsed intent")
        setupAndRunDaemon();
        if (mSocksServerAddress == null) {
            Log.e(LOG_TAG, "Failed to receive the socks server address.")
            m_parentService!!.broadcastVpnStart(false /* success */)
            return 0
        }
        val ctx = context
        val notificationIntent = Intent(ctx, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(ctx, 0, notificationIntent, FLAG_IMMUTABLE)
        val largeIcon = BitmapFactory.decodeResource(ctx!!.resources, R.mipmap.ic_launcher)
        val channelId = createNotificationChannel()
        val builder = NotificationCompat.Builder((ctx), createNotificationChannel())
                .setSmallIcon(R.drawable.ic_stat_notification_icon)
                .setLargeIcon(largeIcon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ctx.getText(R.string.notification_label))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        val notification = builder.build()
        // starting this service on foreground to avoid accidental GC by Android system
        vpnService!!.startForeground(NOTIFICATION_ID, notification)
        return Service.START_STICKY
    }

    private fun createNotificationChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "geph_service"
            val channelName = "Geph background service"
            val chan = NotificationChannel(channelId,
                    channelName, NotificationManager.IMPORTANCE_NONE)
            chan.description = "Geph background service"
            val notificationManager = context!!.getSystemService(NotificationManager::class.java)
            assert(notificationManager != null)
            notificationManager!!.createNotificationChannel(chan)
            return channelId
        }
        return ""
    }

    private fun setupAndRunDaemon(): Process { // Run Geph-Go with uProxy style fail-safe
        Log.e("SETUP", "setupAndRunDaemon");
        // INITIALIZE FILE DESCRIPTOR
        var newFd: ParcelFileDescriptor? = null
        while (newFd == null) {
            Log.d("SETUP", "trying to make fd")
            val builder = m_parentService!!.newBuilder().addAddress("100.64.89.64", 10).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").addDisallowedApplication(context!!.packageName)
            val excludedApps = JSONArray(mExcludeAppsJson)
            for (i in 0 until excludedApps.length()) {
                val packageName = excludedApps.getString(i)
                builder.addDisallowedApplication(packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            newFd = builder.setBlocking(true)
                    .setMtu(1280)
                    .establish()
        }
        tunFd = newFd;
        val newProc = runDaemon(newFd);
        mDaemonProc = newProc;
        val daemonLogs = Thread(Runnable {
            val tag = "Geph"
            val err = newProc.errorStream
            val scanner = Scanner(err)
            while (scanner.hasNextLine()) {
                val line = scanner.nextLine()
                Log.d(tag, line)
            }
            Log.d(tag, "stopping log stuff because the process died")
            vpnService!!.stopForeground(true)
            exitProcess(0)
        })
        daemonLogs.start();
        thread {
            newProc.waitFor();
            exitProcess(0)
        }
        return newProc;
    }



    private fun runDaemon(tunFd: ParcelFileDescriptor): Process {
        val ctx = context
        val daemonBinaryPath = ctx!!.applicationInfo.nativeLibraryDir + "/" + DAEMON_IN_NATIVELIB_DIR
        val credentialsDbPath = ctx.applicationInfo.dataDir + "/geph4-credentials-ng"
        val debugpackDbPath = ctx.applicationInfo.dataDir + "/geph4-debugpack.db"
        val commands: MutableList<String?> = ArrayList()
        commands.add(daemonBinaryPath)
        commands.add("connect")
        commands.add("--username")
        commands.add(mUsername)
        commands.add("--password")
        commands.add(mPassword)
        commands.add("--exit-server")
        commands.add(mExitName)
        commands.add("--credential-cache")
        commands.add(credentialsDbPath)
        commands.add("--debugpack-path")
        commands.add(debugpackDbPath)

        val rpc_key = context!!.getSharedPreferences("GEPH_RPC_KEY", Context.MODE_PRIVATE)
            .getString("GEPH_RPC_KEY", "key-" + UUID.randomUUID().toString())!!;
        with (context!!.getSharedPreferences("GEPH_RPC_KEY", Context.MODE_PRIVATE).edit()) {
            Log.i("RPC_KEY", rpc_key);
            putString("GEPH_RPC_KEY", rpc_key)
            commit()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fd = tunFd.detachFd();
            var hoho = Native.load(LibC::class.java);
            val rv = hoho.fcntl(fd, F_SETFD, 0);
            hoho.dup2(fd, 0);
            commands.add("--vpn-mode")
            commands.add("inherited-fd");
            Os.setenv("GEPH_VPN_FD", "0", true)
            Os.setenv("GEPH_RPC_KEY", rpc_key, true)
        } else {
            commands.add("--vpn-mode");
            commands.add("stdio");
        }
        if ((mListenAll)!!) {
            commands.add("--socks5-listen")
            commands.add("0.0.0.0:9909")
            commands.add("--http-listen")
            commands.add("0.0.0.0:9910")
        }
        if ((mForceBridges)!!) {
            commands.add("--use-bridges")
        }
        if ((mBypassChinese)!!) {
            commands.add("--exclude-prc")
        }
        if (mForceProtocol != null && mForceProtocol!!.length > 0 && mForceProtocol != "null") {
            Log.d(LOG_TAG, "mForceProtocol = " + mForceProtocol)
            commands.add("--force-protocol")
            commands.add(mForceProtocol)
        }
        Log.i(LOG_TAG, commands.toString())

        val pb = ProcessBuilder(commands)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)  {
            return pb.redirectInput(ProcessBuilder.Redirect.INHERIT).start()
        } else {
            val child = pb.start();
            val childStdout = child.inputStream;
            val childStdin = child.outputStream;
            val output = FileOutputStream(tunFd.fileDescriptor);
            val input = FileInputStream(tunFd.fileDescriptor);
            // download
            Thread {
                val body = ByteArray(2048);
                while(true) {
                    val bodylen1 = childStdout.read();
                    val bodylen2 = childStdout.read();
                    val bodylen = bodylen1 + bodylen2*256;
                    var n = 0;
                    while (n < bodylen) {
                        n += childStdout.read(body, n, bodylen - n);
                    }
                    output.write(body, 0, bodylen);
                }
            }.start();
            // upload
            Thread {
                val body = ByteArray(2048);
                while(true) {
                    val n = input.read(body, 2, 2046);
                    body[0] = (n % 256).toByte();
                    body[1] = (n / 256).toByte();
                    childStdin.write(body, 0, n+2);
                    childStdin.flush();
                }
            }.start()
            return child
        }
    }

    fun terminateSocksProxyDaemon() {
        if (mDaemonProc != null) {
            mDaemonProc!!.destroy()
            mDaemonProc = null
        }
    }

    fun restartSocksProxyDaemon() {
        terminateSocksProxyDaemon()
        setupAndRunDaemon()
    }

    // Implementation of android.app.Service.onDestroy
    fun onDestroy() {
        terminateSocksProxyDaemon()
        if (m_tunnelThread == null) {
            return
        }
        // signalStopService should have been called, but in case is was not, call here.
// If signalStopService was not already called, the join may block the calling
// thread for some time.
        signalStopService()
        try {
            m_tunnelThread!!.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        m_tunnelThreadStopSignal = null
        m_tunnelThread = null
        // stop the foreground service
        vpnService!!.stopForeground(true)
    }

    // Signals the runTunnel thread to stopUi. The thread will self-stopUi the service.
// This is the preferred method for stopping the tunnel service:
// 1. VpnService doesn't respond to stopService calls
// 2. The UI will not block while waiting for stopService to return
    fun signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal!!.countDown()
        }
    }

    // Stops the tunnel thread and restarts it with |socksServerAddress|.
    fun restartTunnel(socksServerAddress: String?, socksServerPort: String?, dnsServerPort: String?) {
        Log.i(LOG_TAG, "Restarting tunnel.")
        if (socksServerAddress == null || (socksServerAddress == mSocksServerAddress)) { // Don't reconnect if the socks server address hasn't changed.
            m_parentService!!.broadcastVpnStart(true /* success */)
            return
        }
        mSocksServerAddress = socksServerAddress
        mSocksServerPort = socksServerPort
        mDnsServerPort = dnsServerPort
        m_isReconnecting.set(true)
        // Signaling stopUi to the tunnel thread with the reconnect flag set causes
// the thread to stopUi the tunnel (but not the VPN or the service) and send
// the new SOCKS server address to the DNS resolver before exiting itself.
// When the DNS broadcasts its local address, the tunnel will restart.
        signalStopService()
    }

    //----------------------------------------------------------------------------
// Tunnel.HostService
//----------------------------------------------------------------------------
    val context: Context?
        get() = m_parentService

    val vpnService: VpnService?
        get() = m_parentService

    fun newVpnServiceBuilder(): VpnService.Builder {
        return m_parentService!!.newBuilder()
    }

    fun onDiagnosticMessage(message: String?) {
        Log.d(LOG_TAG, (message)!!)
    }

    fun onTunnelConnected() {
        Log.i(LOG_TAG, "Tunnel connected.")
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun onVpnEstablished() {
        Log.i(LOG_TAG, "VPN established.")
    }

    companion object {
        val NOTIFICATION_ID = 7839214
        @JvmField
        val SOCKS_SERVER_ADDRESS_BASE = "socksServerAddress"
        @JvmField
        val SOCKS_SERVER_PORT_EXTRA = "socksServerPort"
        @JvmField
        val DNS_SERVER_PORT_EXTRA = "dnsServerPort"
        @JvmField
        val USERNAME = "username"
        @JvmField
        val PASSWORD = "password"
        @JvmField
        val EXIT_NAME = "exitName"
        @JvmField
        val LISTEN_ALL = "listenAll"
        @JvmField
        val FORCE_BRIDGES = "forceBridges"
        @JvmField
        val BYPASS_CHINESE = "bypassChinese"
        @JvmField
        val FORCE_PROTOCOL = "forceProtocol"
        @JvmField
        val EXCLUDE_APPS_JSON = "excludeAppsJson"
        private val LOG_TAG = "TunnelManager"
        private val CACHE_DIR_NAME = "geph"
        private val DAEMON_IN_NATIVELIB_DIR = "libgeph.so"
    }

    init {
        m_parentService = parentService
        m_isStopping = AtomicBoolean(false)
        m_isReconnecting = AtomicBoolean(false)
    }
}