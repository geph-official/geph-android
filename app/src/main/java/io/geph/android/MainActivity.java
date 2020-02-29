package io.geph.android;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import io.geph.android.proxbinder.Proxbinder;
import io.geph.android.proxbinder.ProxbinderFactory;
import io.geph.android.tun2socks.TunnelManager;
import io.geph.android.tun2socks.TunnelState;
import io.geph.android.tun2socks.TunnelVpnService;

/**
 * @author j3sawyer
 */
public class MainActivity extends AppCompatActivity implements MainActivityInterface {
    public static final String ACTION_STOP_VPN_SERVICE = "stop_vpn_immediately";

    private static final String PREFS = Constants.PREFS;
    private static final String SP_SERVICE_HALT = Constants.SP_SERVICE_HALT;
    private static final String IS_SIGNED_IN = Constants.SP_IS_SIGNED_IN;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_CODE_PREPARE_VPN = 100;
    private static final int REQUEST_CODE_SETTINGS = 110;
    /**
     * Testing values
     */
    private static final String mSocksServerAddress = "localhost";
    private static final String mSocksServerPort = "9909";
    private static final String mDnsServerPort = "49983";



    private static final String FRONT = "front";
    private static final long TOOLBAR_ACC_ANIM_DURATION = 500;

    /**
     *
     */
    boolean isShow = false;
    int scrollRange = -1;

    private boolean mInternetDown = false;
    private long mInternetDownSince;
    private Handler mUiHandler;
    private View mProgress;
    private WebView mWebView;
    private Receiver vpnReceiver;

    private String mUsername;
    private String mPassword;
    private String mExitName;
    private String mExitKey;
    private Boolean mUseTCP;
    private Boolean mForceBridges;
    private Boolean mBypassChina;

    private void bindActivity() {
        mWebView = findViewById(R.id.main_webview);
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.getSettings().setDomStorageEnabled(true);
        mWebView.getSettings().setAllowFileAccessFromFileURLs(true);
        mWebView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        mWebView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        mWebView.getSettings().setSupportMultipleWindows(true);
        mWebView.setWebChromeClient(new WebChromeClient());
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.e(TAG, url);
                if (url.contains("file")) {
                    return false;
                } else {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                }
                return true;
            }
        });
        mWebView.addJavascriptInterface(this, "Android");

        mWebView.loadUrl("file:///android_asset/htmlbuild/index.html");
        //mWebView.loadUrl("http://10.0.2.2:8100/");
    }

    @JavascriptInterface
    public final void jsCheckAccount(final String uname, final String pwd, final String cbackString) {
        final Context ctx = this.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final String daemonBinaryPath =
                        ctx.getApplicationInfo().nativeLibraryDir + "/libgeph.so";
                ProcessBuilder pb = new ProcessBuilder(daemonBinaryPath, "-loginCheck",
                        "-username", uname,
                        "-password", pwd);
                Log.e(TAG, "START CHECK");
                final Process proc;
                Integer retcode;
                try {
                    proc = pb.start();
                    retcode = proc.waitFor();
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                    retcode = -1;
                }
                Log.e(TAG, cbackString + retcode);
                final Integer lala = retcode;
                runOnUiThread(new Runnable() {
                    public void run() {
                        mWebView.loadUrl("javascript:" + cbackString + "(" + lala + ");void(0);");
                    }
                });
            }
        }).start();
    }

    // JS interfaces
    @JavascriptInterface
    public void jsShowToast(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    @JavascriptInterface
    public int jsStartProxBinder() {
        jsShowToast("attempt");
        Proxbinder pb = ProxbinderFactory.getProxbinder(this);
        jsShowToast("made proxbinder " + pb.PID());
        pbMap.put(pb.PID(), pb);
        return pb.PID();
    }

    @JavascriptInterface
    public void jsStopProxBinder(int pid) {
        Proxbinder pb = pbMap.get(pid);
        if (pb != null) {
            pbMap.remove(pid);
            pb.close();
        }
        jsShowToast("stopped proxbinder " + pid);
    }
    @JavascriptInterface
    public void jsStartDaemon(String uname, String pwd, String exitName, String exitKey, String useTCP, String forceBridges, String bypassChina) {
//        SharedPreferences prefs = this.getSharedPreferences(Constants.PREFS, 0);
//        prefs.edit().putString(Constants.SP_USERNAME, uname)
//                .putString(Constants.SP_PASSWORD, pwd)
//                .putString(Constants.SP_EXIT, exitName)
//                .putString(Constants.SP_EXITKEY, exitKey)
//                .putBoolean(Constants.SP_TCP, useTCP.equals("true"))
//                .putBoolean(Constants.SP_FORCEBRIDGES, forceBridges.equals("true")).apply();
        mUsername = uname;
        mPassword = pwd;
        mExitName = exitName;
        mExitKey = exitKey;
        mUseTCP = useTCP.equals("true");
        mForceBridges = forceBridges.equals("true");
        mBypassChina = bypassChina.equals("true");
        startVpn();
    }
    @JavascriptInterface
    public void jsStopDaemon() {
        stopVpn();
    }

    private Map<Integer, Proxbinder> pbMap = new HashMap<>();

    private static boolean updateScheduled = false;

    private void scheduleUpdateJob(Context context) {
        if (!BuildConfig.BUILD_VARIANT.equals("play") && !updateScheduled) {
            ComponentName serviceComponent = new ComponentName(context, UpdateJobService.class);
            JobInfo.Builder builder = new JobInfo.Builder(0, serviceComponent);
            builder.setPeriodic(20 * 60 * 1000);
            JobScheduler jobScheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
            jobScheduler.schedule(builder.build());
            Log.d(TAG, "JOB SCHEDULED!!!!!!!!!!!!");
            updateScheduled = true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindActivity();
        scheduleUpdateJob(getApplicationContext());

        IntentFilter filter = new IntentFilter();
        filter.addAction(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST);
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_BROADCAST);
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA);
        vpnReceiver = new Receiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mUiHandler = new Handler();

        if (isServiceRunning()) {
            Intent intent = getIntent();
            if (intent != null
                    && intent.getAction() != null
                    && intent.getAction().equals(ACTION_STOP_VPN_SERVICE)) {
                getIntent().setAction(null);
                stopVpn();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "destroy");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver);
        System.exit(0);
    }

    protected void prepareAndStartTunnelService() {
        Log.d(TAG, "Starting VpnService");
        if (hasVpnService()) {
            if (prepareVpnService()) {
                startTunnelService(getApplicationContext());
            }
        } else {
            Log.e(TAG, "Device does not support whole device VPN mode.");
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    protected boolean prepareVpnService() throws ActivityNotFoundException {
        // VpnService: need to display OS user warning. If whole device
        // option is selected and we expect to use VpnService, so show the prompt
        // in the UI before starting the service.
        Intent prepareVpnIntent = VpnService.prepare(getBaseContext());
        if (prepareVpnIntent != null) {
            Log.d(TAG, "Prepare vpn with activity");
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
            return false;
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PREPARE_VPN) {
            if (resultCode == RESULT_OK)
                startTunnelService(getApplicationContext());
            else
                Log.e(TAG, "failed to prepare VPN");
        } else if (requestCode == REQUEST_CODE_SETTINGS) {
            if (resultCode == RESULT_OK) {
                if (data.getBooleanExtra(Constants.RESTART_REQUIRED, false) && isServiceRunning()) {
                    restartVpn();
                }
            }
        }
    }

    private void restartVpn() {
        Toast.makeText(this, getString(R.string.geph_service_restarting), Toast.LENGTH_SHORT).show();
        TunnelState.getTunnelState().getTunnelManager().restartSocksProxyDaemon();
    }

    protected void startTunnelService(Context context) {
        Log.i(TAG, "starting tunnel service");
//        if (isServiceRunning()) {
//            Log.d(TAG, "already running service");
//            TunnelManager tunnelManager = TunnelState.getTunnelState().getTunnelManager();
//            if (tunnelManager != null) {
//                tunnelManager.restartTunnel(mSocksServerAddress, mSocksServerPort, mDnsServerPort);
//            }
//            return;
//        }
        Intent startTunnelVpn = new Intent(context, TunnelVpnService.class);
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_ADDRESS_BASE, mSocksServerAddress);
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_PORT_EXTRA, mSocksServerPort);
        startTunnelVpn.putExtra(TunnelManager.DNS_SERVER_PORT_EXTRA, mDnsServerPort);
        startTunnelVpn.putExtra(TunnelManager.USERNAME, mUsername);
        startTunnelVpn.putExtra(TunnelManager.PASSWORD, mPassword);
        startTunnelVpn.putExtra(TunnelManager.EXIT_NAME, mExitName);
        Log.d(TAG, mExitName);
        startTunnelVpn.putExtra(TunnelManager.EXIT_KEY, mExitKey);
        startTunnelVpn.putExtra(TunnelManager.FORCE_BRIDGES, mForceBridges);
        startTunnelVpn.putExtra(TunnelManager.USE_TCP, mUseTCP);
        startTunnelVpn.putExtra(TunnelManager.BYPASS_CHINA, mBypassChina);
        if (startService(startTunnelVpn) == null) {
            Log.d(TAG, "failed to start tunnel vpn service");
            return;
        }
        TunnelState.getTunnelState().setStartingTunnelManager();
    }

    /**
     * Simple helper to retrieve the service status
     *
     * @return true iff the service is alive and running; false otherwise
     */
    protected boolean isServiceRunning() {
        TunnelState tunnelState = TunnelState.getTunnelState();
        return tunnelState.getStartingTunnelManager() || tunnelState.getTunnelManager() != null;
    }

    /**
     * @return return the fragment associated with the tag FRONT
     */
    private Fragment getContentFragment() {
        return getSupportFragmentManager().findFragmentByTag(FRONT);
    }

    // Returns whether the device supports the tunnel VPN service.
    private boolean hasVpnService() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    @Override
    public void startVpn() {
        prepareAndStartTunnelService();
    }

    @Override
    public void stopVpn() {
        Log.e(TAG, "** ATTEMPTING STOP **");
        TunnelManager currentTunnelManager = TunnelState.getTunnelState().getTunnelManager();
        if (currentTunnelManager != null) {
            currentTunnelManager.signalStopService();
        } else {
            Log.e(TAG, "cannot stop because null!");
        }
    }

    /**
     * @return true iff there is a fragment attached to the main content; false otherwise
     */
    private boolean isContentFragmentAdded() {
        return getContentFragment() != null && getContentFragment().isAdded();
    }

    /**
     * A simple broadcast receiver for receiving messages from TunnelVpnService
     */
    private class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST:
                    break;
                case TunnelVpnService.TUNNEL_VPN_START_BROADCAST:
                    break;
                case TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA:
                    Log.d(TAG, "broadcast networkStateReceiver vpn start success extra");
                    break;
            }
        }
    }
}
