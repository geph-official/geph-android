package io.geph.android

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View.OVER_SCROLL_NEVER
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.frybits.harmony.getHarmonySharedPreferences
import io.geph.android.tun2socks.TunnelManager
import io.geph.android.tun2socks.TunnelState
import io.geph.android.tun2socks.TunnelVpnService
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.serialization.json.*
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------
    // Fields for the main (VPN-based) daemon / service.
    // -------------------------------------------------------------------
    private var mWebView: WebView? = null
    private var vpnReceiver: Receiver? = null

    // Current daemon configuration
    private var daemonArgs: DaemonArgs? = null

    private val accountEventSink by lazy { createAccountEventSink(applicationContext) }


    // -------------------------------------------------------------------
    // The fallback daemon, used ONLY for "daemon_rpc" when the VPN daemon's
    // control Unix socket isn't reachable (e.g. the VPN service isn't running).
    // -------------------------------------------------------------------
    private var fallbackDaemon: GephDaemon? = null

    private val prepareVpnLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnelService(applicationContext)
        }
    }

    private val exportLogsLauncher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        thread {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(collectDebugLogs())
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to export logs", error)
                runOnUiThread {
                    Toast.makeText(this, "Failed to export logs", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Standard activity methods
    // -------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindActivity()
        bindBackHandler()
        accountEventSink.initialize()

        val filter =
                IntentFilter().apply {
                    addAction(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST)
                    addAction(TunnelVpnService.TUNNEL_VPN_START_BROADCAST)
                    addAction(TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA)
                }
        val receiver = Receiver()
        vpnReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Start update service after the daemon is launched

        if (BuildConfig.BUILD_TYPE == "releaseAPK") {
            if (Build.VERSION.SDK_INT >= 33) {
                val permissionState = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
                if (permissionState == PackageManager.PERMISSION_DENIED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        1
                    )
                }
            }
            startAutoUpdateService()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isServiceRunning) {
            val intent = intent
            if (intent != null && intent.action == ACTION_STOP_VPN_SERVICE) {
                intent.action = null
                stopVpn()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnReceiver?.let(::unregisterReceiver)
        fallbackDaemon?.stopDaemon()
        fallbackDaemon = null
        mWebView?.removeJavascriptInterface("Android")
        mWebView?.destroy()
        mWebView = null

        Log.d(TAG, "destroying MainActivity")
    }

    // -------------------------------------------------------------------
    // WebView initialization
    // -------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun bindActivity() {
        mWebView = findViewById(R.id.main_webview)
        val wview = mWebView!!
        wview.settings.javaScriptEnabled = true
        wview.settings.domStorageEnabled = true
        wview.settings.javaScriptCanOpenWindowsAutomatically = true
        wview.settings.setSupportMultipleWindows(false)
        wview.settings.setSupportZoom(false)
        wview.settings.builtInZoomControls = false
        wview.settings.displayZoomControls = false
        wview.setOnTouchListener { _, event ->
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1
        }
        wview.overScrollMode = OVER_SCROLL_NEVER
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        wview.webChromeClient = WebChromeClient()

        // asset loader
        val assetLoader =
                WebViewAssetLoader.Builder()
                        .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
                        .build()

        wview.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null) {
                            throw RuntimeException("request is null")
                        }
                        val resp = assetLoader.shouldInterceptRequest(request.url)
                        if (request.url.toString().endsWith("js")) {
                            resp?.setMimeType("text/javascript")
                        }
                        return resp
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val initJsString =
                                application.assets.open("init.js").bufferedReader().use {
                                    it.readText()
                                }
                        wview.evaluateJavascript(initJsString, null)
                    }

                    override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        Log.e(TAG, url)
                        return if (url.contains("appassets.androidplatform.net")) {
                            false
                        } else {
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(i)
                            true
                        }
                    }
                }

        wview.addJavascriptInterface(this, "Android")
        wview.loadUrl("https://appassets.androidplatform.net/htmlbuild/index.html")
    }

    private fun bindBackHandler() {
        onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val webView = mWebView
                        if (webView != null && webView.canGoBack()) {
                            webView.goBack()
                            return
                        }

                        // No WebView history left; let the default activity back behavior run.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
        )
    }

    // -------------------------------------------------------------------
    // JavaScript interface: dispatch calls from WebView to Kotlin
    // -------------------------------------------------------------------
    @JavascriptInterface
    fun jsVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun jsHasPlay(): String {
        return try {
            application.packageManager.getPackageInfo("com.android.vending", 0)
            "true"
        } catch (e: Exception) {
            "false"
        }
    }

    @JavascriptInterface
    fun callRpc(verb: String, jsonArgs: String, cback: String) {
        val wbview = mWebView!!
        thread {
            try {
                val result = callRpcInner(verb, jsonArgs)
                runOnUiThread {
                    wbview.evaluateJavascript(
                            "$cback[0](\"${StringEscapeUtils.escapeEcmaScript(result)}\")",
                            null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    wbview.evaluateJavascript(
                            "$cback[1](\"${StringEscapeUtils.escapeEcmaScript(e.message)}\")",
                            null
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // The actual handling of RPC calls
    // -------------------------------------------------------------------
    fun callRpcInner(verb: String?, jsonArgs: String?): String {
        val args = JSONArray(jsonArgs)
        Log.e(TAG, verb!!)
        when (verb) {
            "start_daemon" -> {
                // Create daemon args and start VPN service
                rpcStartDaemon(args.getJSONObject(0))
                return "null"
            }
            "restart_daemon" -> {
                throw Exception("restarting not supported")
            }
            "stop_daemon" -> {
                // Stop the VPN service
                stopVpn()
                return "null"
            }
            "daemon_rpc" -> {
                val command = args.getString(0)
                Log.d(TAG, "daemon rpc: ${command}")
                val response = try {
                    localSocketRpc(TunnelManager.vpnControlSockPath(applicationContext), command)
                } catch (e: Exception) {
                    Log.w(TAG, "daemon_rpc unix socket failed, falling back", e)
                    if (!command.contains("\"method\":\"stop\"")) {
                        fallbackDaemonRpc(command)
                    } else {
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"\"}"
                    }
                }
                observeAccountStatusResponse(command, response)
                return response
            }
            "get_app_list" -> {
                return rpcGetAppList()
            }
            "get_app_icon" -> {
                return rpcGetAppIcon(args.getString(0))
            }
            "export_logs" -> {
                rpcExportLogs()
                return "null"
            }
            "get_debug_logs" -> {
                return JSONObject.quote(collectDebugLogs())
            }
            "open_browser" -> {
                Log.d(TAG, "open browser")
                val url = args.getString(0)
                runOnUiThread {
                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(this, Uri.parse(url))
                }
                return "null"
            }
        }
        throw Exception("Unknown RPC verb: $verb")
    }

    private fun observeAccountStatusResponse(command: String, response: String) {
        runCatching {
            val request = JSONObject(command)
            if (request.optString("method") != "broker_rpc") {
                return
            }

            val params = request.optJSONArray("params") ?: return
            if (params.optString(0) != "get_user_info_by_cred") {
                return
            }

            val userInfo = JSONObject(response).optJSONObject("result") ?: return
            recordAccountLevel(userInfo)
        }.onFailure { error ->
            Log.w(TAG, "Failed to observe account status response", error)
        }
    }

    private fun recordAccountLevel(userInfo: JSONObject) {
        val userId =
                if (userInfo.has("user_id") && !userInfo.isNull("user_id")) {
                    userInfo.optLong("user_id").toString()
                } else {
                    "unknown"
                }
        val plusExpiresUnix =
                if (userInfo.has("plus_expires_unix") && !userInfo.isNull("plus_expires_unix")) {
                    userInfo.optLong("plus_expires_unix", 0L)
                } else {
                    0L
                }
        val level = if (plusExpiresUnix > 0L) "Plus" else "Free"
        val prefs = applicationContext.getSharedPreferences("account-level-events", MODE_PRIVATE)
        val key = "last-level:$userId"
        val previousLevel = prefs.getString(key, null)

        if (previousLevel == "Free" && level == "Plus") {
            accountEventSink.onPlusTransition()
        }

        prefs.edit().putString(key, level).apply()
    }

    // -------------------------------------------------------------------
    // "start_daemon" calls here: sets up the main VPN-based service
    // -------------------------------------------------------------------
    private fun rpcStartDaemon(args: JSONObject) {
        try {
            val jsonString = args.toString()
            val json = Json { ignoreUnknownKeys = true }
            daemonArgs = json.decodeFromString(DaemonArgs.serializer(), jsonString)

            // Store the DaemonArgs in shared preferences as JSON for the VPN service
            val prefs = applicationContext.getHarmonySharedPreferences("daemon")
            prefs.edit()
                    .putString(
                            TunnelManager.DAEMON_ARGS,
                            Json.encodeToString(DaemonArgs.serializer(), daemonArgs!!)
                    )
                    .apply()

            // Start the VPN service
            startVpn()

            // Block until daemon is reachable on its control socket (mirrors desktop wait_daemon_start)
            val socketPath = TunnelManager.vpnControlSockPath(applicationContext)
            if (!waitForLocalSocket(socketPath, 30_000)) {
                throw Exception("daemon did not become reachable within 30 seconds")
            }
            Thread.sleep(500)
            return

        } catch (e: Exception) {
            Log.e(TAG, "Error starting daemon: ${e.message}", e)
            throw e
        }
    }

    // -------------------------------------------------------------------
    // Start the auto-update service with the daemon RPC function
    // -------------------------------------------------------------------
    private fun startAutoUpdateService() {
        ensureFallbackDaemon()
        val daemonRpcFunction = { method: String, args: JSONArray ->
            val jsonRequest =
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", 100)
                        put("method", method)


                        put("params", args)
                    }

            callRpcInner("daemon_rpc", JSONArray().put(jsonRequest.toString()).toString())
        }

        thread {
            UpdateChecker(
                applicationContext,
                daemonRpcFunction,
            ).checkForUpdates()
        }
    }

    // -------------------------------------------------------------------
    // "export_logs" calls here
    // -------------------------------------------------------------------
    @JavascriptInterface
    fun rpcExportLogs() {
        exportLogsLauncher.launch("geph-debugpack.log")
    }

    // -------------------------------------------------------------------
    // Example: get list of installed apps
    // -------------------------------------------------------------------
    fun rpcGetAppList(): String {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val bigArray = JSONArray()
        for (packageInfo in packages) {
            if (packageInfo.packageName == applicationContext.packageName) continue
            val jsonObject =
                    JSONObject().apply {
                        put("id", packageInfo.packageName)
                        put("friendly_name", packageInfo.loadLabel(pm))
                    }
            bigArray.put(jsonObject)
        }
        return bigArray.toString()
    }

    // -------------------------------------------------------------------
    // Example: get app icon
    // -------------------------------------------------------------------
    fun rpcGetAppIcon(packageName: String?): String {
        val pm = packageManager
        val icon = drawableToBitmap(pm.getApplicationIcon(packageName!!))
        val b64 = encodeToBase64(icon).replace("\n", "")
        return "\"data:image/png;base64,$b64\""
    }

    // -------------------------------------------------------------------
    // Methods for the main (VPN-based) daemon
    // -------------------------------------------------------------------
    protected fun prepareAndStartTunnelService() {
        Log.d(TAG, "Starting VpnService")
        if (prepareVpnService()) {
            startTunnelService(applicationContext)
        }
    }

    @Throws(ActivityNotFoundException::class)
    protected fun prepareVpnService(): Boolean {
        val prepareVpnIntent = VpnService.prepare(baseContext)
        if (prepareVpnIntent != null) {
            prepareVpnLauncher.launch(prepareVpnIntent)
            return false
        }
        return true
    }

    protected fun startTunnelService(context: Context?) {
        Log.i(TAG, "Starting tunnel service")
        val startTunnelVpn = Intent(context, TunnelVpnService::class.java)
        try {
            ContextCompat.startForegroundService(this, startTunnelVpn)
            TunnelState.getTunnelState().setStartingTunnelManager()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel vpn service", e)
        }
    }

    // -------------------------------------------------------------------
    // VPN controls
    // -------------------------------------------------------------------
    fun startVpn() {
        prepareAndStartTunnelService()
    }

    fun stopVpn() {
        Log.e(TAG, "Attempting to stop VPN")
        val stopTunnelVpn =
                Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP_VPN
                }
        startService(stopTunnelVpn)
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------
    /**
     * Simple helper to retrieve the service status
     * @return true iff the service is alive and running; false otherwise
     */
    protected val isServiceRunning: Boolean
        get() {
            val tunnelState = TunnelState.getTunnelState()
            return tunnelState.startingTunnelManager || tunnelState.tunnelManager != null
        }

    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST -> {
                    // handle if you want
                }
                TunnelVpnService.TUNNEL_VPN_START_BROADCAST -> {
                    // handle if you want
                }
                TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA -> {
                    Log.d(TAG, "broadcast: TUNNEL_VPN_START_SUCCESS_EXTRA")
                }
            }
        }
    }

    @Synchronized
    private fun ensureFallbackDaemon(): GephDaemon {
        val current = fallbackDaemon
        if (current != null && current.isAlive) {
            return current
        }
        current?.stopDaemon()
        fallbackDaemon = null

        val fallbackConfig = buildJsonObject {
            for ((key, value) in configTemplate()) {
                put(key, value)
            }
            put("cache", applicationContext.filesDir.resolve("cache_fallback").absolutePath)
            put("control_listen_unix", TunnelManager.fallbackControlSockPath(applicationContext))
        }
        return GephDaemon(applicationContext, fallbackConfig).also {
            Log.d(TAG, "STARTING FALLBACK DAEMON")
            fallbackDaemon = it
        }
    }

    @Synchronized
    private fun fallbackDaemonRpc(command: String): String {
        val socketPath = TunnelManager.fallbackControlSockPath(applicationContext)

        ensureFallbackDaemon()
        if (waitForLocalSocket(socketPath, 5_000)) {
            try {
                return localSocketRpc(socketPath, command)
            } catch (e: Exception) {
                Log.w(TAG, "fallback rpc failed, restarting daemon", e)
            }
        } else {
            Log.w(TAG, "fallback daemon did not bind socket in time, restarting")
        }

        fallbackDaemon?.stopDaemon()
        fallbackDaemon = null
        ensureFallbackDaemon()
        if (!waitForLocalSocket(socketPath, 5_000)) {
            throw IllegalStateException("fallback daemon did not become reachable")
        }
        return localSocketRpc(socketPath, command)
    }

    /**
     * Connect to a daemon's control RPC Unix socket, send one line-delimited
     * JSON-RPC request, and read back the one-line response.
     */
    private fun localSocketRpc(socketPath: String, command: String): String {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val out = PrintWriter(socket.outputStream, true)
            val input = BufferedReader(InputStreamReader(socket.inputStream))
            out.println(command)
            return input.readLine() ?: throw IOException("daemon closed connection without responding")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Poll-connect to the given Unix socket path until it accepts a connection
     * or the timeout elapses. Used to wait for a freshly-spawned daemon to
     * bind its control socket.
     */
    private fun waitForLocalSocket(socketPath: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sleepMs = 5L
        while (System.currentTimeMillis() < deadline) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                return true
            } catch (_: Exception) {
                Thread.sleep(sleepMs)
                sleepMs = (sleepMs * 2).coerceAtMost(500)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun collectDebugLogs(): String {
        return runCatching {
            val process = ProcessBuilder("logcat", "-d")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            "Error: ${error.message}"
        }
    }

    // -------------------------------------------------------------------
    // Companion object and other constants
    // -------------------------------------------------------------------
    companion object {
        const val ACTION_STOP_VPN_SERVICE = "stop_vpn_immediately"
        private val TAG = MainActivity::class.java.simpleName

        fun encodeToBase64(image: Bitmap): String {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val b = baos.toByteArray()
            return Base64.encodeToString(b, Base64.DEFAULT)
        }

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            val width = 32
            val height = 32
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }
}
