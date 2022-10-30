package io.geph.android

//import io.geph.android.tun2socks.TunnelManager.restartSocksProxyDaemon
//import io.geph.android.tun2socks.TunnelManager.signalStopService
import android.support.v7.app.AppCompatActivity
import android.annotation.SuppressLint

import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import io.geph.android.proxbinder.Proxbinder
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Bundle
import io.geph.android.tun2socks.TunnelVpnService
import android.support.v4.content.LocalBroadcastManager
import android.annotation.TargetApi
import android.os.Build
import android.net.VpnService
import android.content.*
import android.graphics.Canvas
import io.geph.android.tun2socks.TunnelManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.support.v4.app.Fragment
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import io.geph.android.tun2socks.TunnelState
import java.io.*
import java.lang.Exception
import java.lang.StringBuilder
import java.util.ArrayList
import java.util.HashMap
import org.apache.commons.text.StringEscapeUtils;
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
    private var mUseTCP: Boolean? = null
    private var mListenAll: Boolean? = null
    private var mForceBridges: Boolean? = null
    private var syncStatusJson: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindActivity() {
        mWebView = findViewById(R.id.main_webview)
        val wview = mWebView!!;
        wview.getSettings().javaScriptEnabled = true
        wview.getSettings().domStorageEnabled = true
        wview.getSettings().allowFileAccessFromFileURLs = true
        wview.getSettings().allowUniversalAccessFromFileURLs = true
        wview.getSettings().javaScriptCanOpenWindowsAutomatically = true
        wview.getSettings().setSupportMultipleWindows(false)
        WebView.setWebContentsDebuggingEnabled(true)
        wview.setWebChromeClient(WebChromeClient())
        wview.setWebViewClient(object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
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
                if (url.contains("file")) {
                    return false
                } else {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(i)
                }
                return true
            }
        })
        wview.addJavascriptInterface(this, "Android")
        wview.clearCache(true)
        wview.loadUrl("file:///android_asset/htmlbuild/index.html")
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
                Log.d(TAG, "error is back");
                runOnUiThread {
                    wbview.evaluateJavascript(
                        cback + "[1](\"" + StringEscapeUtils.escapeEcmaScript(e.toString()) + "\")",
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

    fun rpcSync(args: JSONArray): String {
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
//                    if (args.getBoolean(2)) {
//                        commands.add("--force")
//                    }
        val pb = ProcessBuilder(commands)
        Log.d(TAG, "START CHECK")
        val proc: Process
        var retcode: String
        try {
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
            proc.waitFor()
            Log.d(TAG, "RETCODE: " + retcode)
        } catch (e: Exception) {
            Log.d(TAG, e.toString())
            retcode = "{\"error\": \"internal\"}"
        }
        return retcode
    }

    private fun rpcStartDaemon(args: JSONObject) {
        mUsername = args.getString("username")
        mPassword = args.getString("password")
        mExitName = args.getString("exit_hostname")
        mListenAll = args.getBoolean("listen_all")
        mForceBridges = args.getBoolean("force_bridges")
        //mBypassChinese = bypassChinese.equals("true");
        mUseTCP = false
        mExcludeAppsJson = args.getJSONArray("app_whitelist").toString()
        Log.d("EXCLUDEAPPS", mExcludeAppsJson!!)
        startVpn()
    }

    @JavascriptInterface
    fun rpcExportLogs() {
        val ctx = this.applicationContext
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TITLE, "geph-debug.txt")
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
    private fun scheduleUpdateJob(context: Context) {
        if (BuildConfig.BUILD_VARIANT != "play" && !updateScheduled) {
            val serviceComponent = ComponentName(context, UpdateJobService::class.java)
            val builder = JobInfo.Builder(0, serviceComponent)
            builder.setPeriodic((20 * 60 * 1000).toLong())
            val jobScheduler = context.getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.schedule(builder.build())
            Log.d(TAG, "JOB SCHEDULED!!!!!!!!!!!!")
            updateScheduled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindActivity()
        scheduleUpdateJob(applicationContext)
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
                val logPath = ctx.applicationInfo.dataDir + "/logs.txt"
                try {
                    FileInputStream(logPath).use { `is` ->
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
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_ADDRESS_BASE, mSocksServerAddress)
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_PORT_EXTRA, mSocksServerPort)
        startTunnelVpn.putExtra(TunnelManager.DNS_SERVER_PORT_EXTRA, mDnsServerPort)
        startTunnelVpn.putExtra(TunnelManager.USERNAME, mUsername)
        startTunnelVpn.putExtra(TunnelManager.PASSWORD, mPassword)
        startTunnelVpn.putExtra(TunnelManager.EXIT_NAME, mExitName)
        Log.d(TAG, mExitName!!)
        startTunnelVpn.putExtra(TunnelManager.FORCE_BRIDGES, mForceBridges)
        startTunnelVpn.putExtra(TunnelManager.LISTEN_ALL, mListenAll)
        startTunnelVpn.putExtra(TunnelManager.BYPASS_CHINESE, mBypassChinese)
        startTunnelVpn.putExtra(TunnelManager.USE_TCP, mUseTCP)
        startTunnelVpn.putExtra(TunnelManager.EXCLUDE_APPS_JSON, mExcludeAppsJson)
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