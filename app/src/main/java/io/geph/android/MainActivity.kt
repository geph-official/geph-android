package io.geph.android

//import io.geph.android.tun2socks.TunnelManager.restartSocksProxyDaemon
//import io.geph.android.tun2socks.TunnelManager.signalStopService

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.system.Os
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import io.geph.android.proxbinder.Proxbinder
import io.geph.android.tun2socks.TunnelManager
import io.geph.android.tun2socks.TunnelState
import io.geph.android.tun2socks.TunnelVpnService
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread


/**
 * @author j3sawyer
 */
class MainActivity : AppCompatActivity(), MainActivityInterface {
    /**
     *
     */
    var isShow = false
    var scrollRange = -1
    private val mInternetDown = false
    private val mInternetDownSince: Long = 0
    private var mUiHandler: Handler? = null
    private val mProgress: View? = null
    private var mWebView: WebView? = null
    private var vpnReceiver: Receiver? = null
    private var mUsername: String? = null
    private var mPassword: String? = null
    private var mExitName: String? = null
    private var mExcludeAppsJson: String? = null
    private val mBypassChinese: Boolean? = null
    private var mForceProtocol: String? = null
    private var mListenAll: Boolean? = null
    private var mForceBridges: Boolean? = null
    private var syncStatusJson: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindActivity() {
        mWebView = findViewById(R.id.main_webview)
        val wview = mWebView!!;
        wview.getSettings().javaScriptEnabled = true
        wview.getSettings().domStorageEnabled = true
        wview.getSettings().javaScriptCanOpenWindowsAutomatically = true
        wview.getSettings().setSupportMultipleWindows(false)
        WebView.setWebContentsDebuggingEnabled(true)
        wview.setWebChromeClient(WebChromeClient())
        // asset loader
        val assetLoader = WebViewAssetLoader.Builder().addPathHandler("/", AssetsPathHandler(this)).build();
        wview.setWebViewClient(object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request != null) {
                    val resp = assetLoader.shouldInterceptRequest(request!!.getUrl());
                    if (request.getUrl().toString().endsWith("js")) {
                        if (resp != null) {
                            resp.setMimeType("text/javascript");
                        }
                    }
                    return resp;
                } else {
                    throw java.lang.RuntimeException("request is null")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val initJsString =
                    application.assets.open("init.js").bufferedReader().use { it.readText() };
                wview.evaluateJavascript(initJsString, null);
            }


            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                Log.e(TAG, url)
                if (url.contains("appassets.androidplatform.net")) {
                    return false
                } else {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(i)
                }
                return true
            }
        })
        wview.addJavascriptInterface(this, "Android")
//        wview.clearCache(true)
        wview.loadUrl("https://appassets.androidplatform.net/htmlbuild/index.html")
    }

    @JavascriptInterface
    fun jsVersion(): String {
        return BuildConfig.VERSION_NAME
    }

    @JavascriptInterface
    fun jsHasPlay(): String {
        try {
            application.packageManager.getPackageInfo("com.android.vending", 0)
            return "true"
        } catch (e: Exception) {
            return "false"
        }
    }

    @JavascriptInterface
    fun callRpc(verb: String, jsonArgs: String, cback: String) {
        val wbview = mWebView!!;
        thread {
            try {
                val result = callRpcInner(verb, jsonArgs);
                Log.d(TAG, "result is back " + result);
                runOnUiThread {
                    wbview.evaluateJavascript(
                        cback + "[0](\"" + StringEscapeUtils.escapeEcmaScript(result) + "\")",
                        null
                    );
                };
            } catch (e: Exception) {
                Log.d(TAG, "error is back " + e.toString());
                runOnUiThread {
                    wbview.evaluateJavascript(
                        cback + "[1](\"" + StringEscapeUtils.escapeEcmaScript(e.message) + "\")",
                        null
                    );
                }
            }
        }
    }


    fun callRpcInner(verb: String?, jsonArgs: String?): String {
        val args = JSONArray(jsonArgs)
        Log.e(TAG, verb!!)
        when (verb) {
            "sync" -> {
                return rpcSync(args)
            }
            "start_daemon" -> {
                rpcStartDaemon(args.getJSONObject(0))
                return "null"
            }
            "stop_daemon" -> {
                // No-op, the RPC call already destroys the daemon
                return "null"
            }

            "binder_rpc" -> {
                return rpcBinder(args)
            }

            "daemon_rpc" -> {
                val rawJsonString = args.getString(0);
                with(URL("http://127.0.0.1:9809").openConnection() as HttpURLConnection) {
                    requestMethod = "POST"

                    val wr = OutputStreamWriter(outputStream);
                    wr.write(rawJsonString)
                    wr.flush()

                    BufferedReader(InputStreamReader(inputStream)).use {
                        val response = StringBuffer()

                        var inputLine = it.readLine()
                        while (inputLine != null) {
                            response.append(inputLine)
                            inputLine = it.readLine()
                        }
                        return response.toString()
                    }
                }
            }

            "get_app_list" -> {
                return rpcGetAppList()
            }
            "get_app_icon" -> {
                return rpcGetAppIcon(args.getString(0))
            }
            "export_logs" -> {
                rpcExportLogs()
                return "{}"
            }
        }
        return "{}"
    }

    fun rpcBinder(args: JSONArray): String {
        val rawJsonString = args.getString(0);
        Log.d("rpcBinder", rawJsonString);
        val ctx = applicationContext;
        val dbPath = ctx.applicationInfo.dataDir + "/geph4-credentials-ng"
        val daemonBinaryPath = ctx.applicationInfo.nativeLibraryDir + "/libgeph.so"
        val commands: MutableList<String> = ArrayList()
        commands.add(daemonBinaryPath)
        commands.add("binder-proxy")
        val pb = ProcessBuilder(commands)
        val proc = pb.start()
        proc.outputStream.write(rawJsonString.toByteArray());
        proc.outputStream.write("\n".toByteArray());
        proc.outputStream.flush();
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        return reader.readLine()
    }

    fun rpcSync(args: JSONArray): String {
        Os.setenv("RUST_LOG", "error", true)
        val ctx = applicationContext;
        val dbPath = ctx.applicationInfo.dataDir + "/geph4-credentials-ng"
        val daemonBinaryPath = ctx.applicationInfo.nativeLibraryDir + "/libgeph.so"
        val commands: MutableList<String> = ArrayList()
        commands.add(daemonBinaryPath)
        commands.add("sync")
        commands.add("--username")
        commands.add(args.getString(0))
        commands.add("--password")
        commands.add(args.getString(1))
        commands.add("--credential-cache")
        commands.add(dbPath)
        if (args.getBoolean(2)) {
            commands.add("--force")
        }
        val pb = ProcessBuilder(commands)
        Log.d(TAG, "START CHECK")
        val proc: Process
        var retcode: String
        proc = pb.start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val builder = StringBuilder()
        var line: String? = null
        while (reader.readLine().also { line = it } != null) {
            Log.e(TAG, line!!)
            builder.append(line)
            builder.append(System.getProperty("line.separator"))
        }
        Log.d(TAG, "DONE")
        retcode = builder.toString()
        val stderr = BufferedReader(InputStreamReader(proc.errorStream)).readLine();
        proc.waitFor()
        if (stderr != null && stderr.length > 10) {
            Log.e(TAG, "stderr: " + stderr);
            throw java.lang.RuntimeException(stderr.trim())
        }
        Log.d(TAG, "RETCODE: " + retcode)
        return retcode
    }

    private fun rpcStartDaemon(args: JSONObject) {
        mUsername = args.getString("username")
        mPassword = args.getString("password")
        mExitName = args.getString("exit_hostname")
        mListenAll = args.getBoolean("listen_all")
        mForceBridges = args.getBoolean("force_bridges")
        //mBypassChinese = bypassChinese.equals("true");
        mForceProtocol = args.getString("force_protocol")
        mExcludeAppsJson = args.getJSONArray("app_whitelist").toString()
        Log.d("EXCLUDEAPPS", mExcludeAppsJson!!)
        startVpn()
    }

    @JavascriptInterface
    fun rpcExportLogs() {
        val ctx = this.applicationContext
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/vnd.sqlite3"
        intent.putExtra(Intent.EXTRA_TITLE, "geph-debugpack.db")
        startActivityForResult(intent, CREATE_FILE)
    }

    fun rpcGetAppList(): String {
        val pm = packageManager
        //get a list of installed apps.
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val bigArray = JSONArray()
        for (packageInfo in packages) {
            if (packageInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 || packageInfo.packageName == applicationContext.packageName) {
                continue
            }
            val jsonObject = JSONObject()
            jsonObject.put("id", packageInfo.packageName)
            jsonObject.put("friendly_name", packageInfo.loadLabel(pm))
            bigArray.put(jsonObject)
        }
        return bigArray.toString()
    }

    fun rpcGetAppIcon(packageName: String?): String {
        val pm = packageManager
        val icon = drawableToBitmap(pm.getApplicationIcon(packageName!!))
        val b64 = encodeToBase64(icon).replace("\n", "");
        val rr = "\"data:image/png;base64,$b64\""
        return rr
    }

    private val pbMap: MutableMap<Int, Proxbinder> = HashMap()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindActivity()

        val filter = IntentFilter()
        filter.addAction(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST)
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_BROADCAST)
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA)
        vpnReceiver = Receiver()
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver!!, filter)
    }

    override fun onResume() {
        super.onResume()
        mUiHandler = Handler()
        if (isServiceRunning) {
            val intent = intent
            if (intent != null && intent.action != null && intent.action == ACTION_STOP_VPN_SERVICE) {
                getIntent().action = null
                stopVpn()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "destroy")
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver!!)
        System.exit(0)
    }

    protected fun prepareAndStartTunnelService() {
        Log.d(TAG, "Starting VpnService")
        if (hasVpnService()) {
            if (prepareVpnService()) {
                Log.d(TAG, "about to REALLY start the tunnel service")
                startTunnelService(applicationContext)
            }
        } else {
            Log.e(TAG, "Device does not support whole device VPN mode.")
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Throws(ActivityNotFoundException::class)
    protected fun prepareVpnService(): Boolean {
        Log.d(TAG, "about to prepare the tunnel service")
        // VpnService: need to display OS user warning. If whole device
        // option is selected and we expect to use VpnService, so show the prompt
        // in the UI before starting the service.
        val prepareVpnIntent = VpnService.prepare(baseContext)
        if (prepareVpnIntent != null) {
            Log.d(TAG, "Prepare vpn with activity")
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PREPARE_VPN) {
            startTunnelService(applicationContext)
//            if (resultCode == RESULT_OK) startTunnelService(applicationContext) else Log.e(
//                TAG,
//                "failed to prepare VPN " + resultCode.toString()
//            )
        } else if (requestCode == REQUEST_CODE_SETTINGS) {
            if (resultCode == RESULT_OK) {
                if (data!!.getBooleanExtra(Constants.RESTART_REQUIRED, false) && isServiceRunning) {
                    restartVpn()
                }
            }
        } else if (requestCode == CREATE_FILE) {
            if (resultCode == RESULT_OK) {
                val ctx = applicationContext
                val daemonBinaryPath: String = applicationInfo.nativeLibraryDir + "/libgeph.so"
                val debugPackPath = applicationInfo.dataDir + "/geph4-debugpack.db"
                val debugPackExportedPath = applicationInfo.dataDir + "/geph4-debugpack-exported.db"
                val pb = ProcessBuilder(daemonBinaryPath, "debugpack", "--export-to", debugPackExportedPath, "--debugpack-path", debugPackPath)
                Thread {
                    Log.d(TAG, "export process started from " + debugPackPath  + " => " + debugPackExportedPath);
                    try {
//                        pb.inheritIO();
                        Log.d(TAG, "export gonna start the process");
                        val process = pb.start()
                        Log.d(TAG, "export gonna wait for process to return");
                        val retval = process.waitFor()
                        Log.d(TAG, "export process returned " + retval.toString());
                    } catch (e: IOException) {
                        Log.d(TAG, "export about to do failure");
                        Log.e(TAG, "Export debugpack failed " + e.message!!)
                        e.printStackTrace()
                    }
                try {
                    FileInputStream(debugPackExportedPath).use { `is` ->
                        contentResolver.openOutputStream(
                                data!!.data!!
                        ).use { os ->
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (`is`.read(buffer).also { length = it } > 0) {
                                assert(os != null)
                                os!!.write(buffer, 0, length)
                            }
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                }.start()

            }
        }
    }

    private fun restartVpn() {
        Toast.makeText(this, getString(R.string.geph_service_restarting), Toast.LENGTH_SHORT).show()
        TunnelState.getTunnelState().tunnelManager.restartSocksProxyDaemon()
    }

    protected fun startTunnelService(context: Context?) {
        Log.i(TAG, "starting tunnel service")
        val startTunnelVpn = Intent(context, TunnelVpnService::class.java)
        Log.d(TAG, "*** GONNA SET PREFERENCES ***  "+ (context == null).toString())
        val prefs = context!!.getSharedPreferences("daemon", Context.MODE_PRIVATE);
        Log.d(TAG, "*** REALLY GONNA SET PREFERENCES ***  ")
        with (prefs.edit()) {
            putString(TunnelManager.SOCKS_SERVER_ADDRESS_BASE, mSocksServerAddress)
            putString(TunnelManager.SOCKS_SERVER_PORT_EXTRA, mSocksServerPort)
            putString(TunnelManager.DNS_SERVER_PORT_EXTRA, mDnsServerPort)
            putString(TunnelManager.USERNAME, mUsername)
            putString(TunnelManager.PASSWORD, mPassword)
            putString(TunnelManager.EXIT_NAME, mExitName)
            Log.d(TAG, "*** mForceBridges *** "  + mForceBridges.toString())
            putBoolean(TunnelManager.FORCE_BRIDGES, mForceBridges!!)
            Log.d(TAG, "*** mListenAll *** "  + mListenAll.toString())
            putBoolean(TunnelManager.LISTEN_ALL, mListenAll!!)
            putString(TunnelManager.FORCE_PROTOCOL, mForceProtocol)
            putString(TunnelManager.EXCLUDE_APPS_JSON, mExcludeAppsJson)
            apply()
        }

        Log.d(TAG, "*** SET PREFERENCES ***")

        if (startService(startTunnelVpn) == null) {
            Log.d(TAG, "failed to start tunnel vpn service")
            return
        }
        TunnelState.getTunnelState().setStartingTunnelManager()
    }

    /**
     * Simple helper to retrieve the service status
     *
     * @return true iff the service is alive and running; false otherwise
     */
    protected val isServiceRunning: Boolean
        protected get() {
            val tunnelState = TunnelState.getTunnelState()
            return tunnelState.startingTunnelManager || tunnelState.tunnelManager != null
        }

    /**
     * @return return the fragment associated with the tag FRONT
     */
    private val contentFragment: Fragment?
        private get() = supportFragmentManager.findFragmentByTag(FRONT)

    // Returns whether the device supports the tunnel VPN service.
    private fun hasVpnService(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    override fun startVpn() {
        Log.d(TAG, "about to start the tunnel service")
        prepareAndStartTunnelService()
    }

    override fun stopVpn() {
        Log.e(TAG, "** ATTEMPTING STOP **")
        val currentTunnelManager = TunnelState.getTunnelState().tunnelManager
        currentTunnelManager?.signalStopService()
            ?: Log.e(TAG, "cannot stop because null!")
    }

    /**
     * @return true iff there is a fragment attached to the main content; false otherwise
     */
    private val isContentFragmentAdded: Boolean
        private get() = contentFragment != null && contentFragment!!.isAdded

    /**
     * A simple broadcast receiver for receiving messages from TunnelVpnService
     */
    private inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST -> {}
                TunnelVpnService.TUNNEL_VPN_START_BROADCAST -> {}
                TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA -> Log.d(
                    TAG,
                    "broadcast networkStateReceiver vpn start success extra"
                )
            }
        }
    }

    companion object {
        const val ACTION_STOP_VPN_SERVICE = "stop_vpn_immediately"
        private const val PREFS = Constants.PREFS
        private const val SP_SERVICE_HALT = Constants.SP_SERVICE_HALT
        private const val IS_SIGNED_IN = Constants.SP_IS_SIGNED_IN
        private val TAG = MainActivity::class.java.simpleName
        private const val REQUEST_CODE_PREPARE_VPN = 100
        private const val REQUEST_CODE_SETTINGS = 110

        /**
         * Testing values
         */
        private const val mSocksServerAddress = "127.0.0.1"
        private const val mSocksServerPort = "9909"
        private const val mDnsServerPort = "49983"
        private const val FRONT = "front"
        private const val TOOLBAR_ACC_ANIM_DURATION: Long = 500
        private const val CREATE_FILE = 1
        fun encodeToBase64(image: Bitmap): String {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 0, baos)
            val b = baos.toByteArray()
            val imageEncoded = Base64.encodeToString(b, Base64.DEFAULT)

            return imageEncoded
        }

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            var width = 32
            var height = 32
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        private var updateScheduled = false
    }
}