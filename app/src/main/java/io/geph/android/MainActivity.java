package io.geph.android;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;

import io.geph.android.api.models.Captcha;
import io.geph.android.proxbinder.Proxbinder;
import io.geph.android.proxbinder.ProxbinderFactory;
import io.geph.android.tun2socks.TunnelManager;
import io.geph.android.tun2socks.TunnelState;
import io.geph.android.tun2socks.TunnelVpnService;
import io.geph.android.ui.AboutDialogFragment;
import io.geph.android.ui.FeatureFragment;
import io.geph.android.ui.InvalidCredentialDialogFragment;
import io.geph.android.ui.LoginFragment;
import io.geph.android.ui.RegistrationFragment;
import io.geph.android.ui.SettingsActivity;
import io.geph.android.ui.SimpleUiControl;

import static io.geph.android.Constants.SP_PASSWORD;
import static io.geph.android.Constants.SP_USERNAME;
import static io.geph.android.ui.RegistrationFragment.CAPTCHA_ID_EXTRA;
import static io.geph.android.ui.RegistrationFragment.CAPTCHA_IMAGE_EXTRA;

/**
 * @author j3sawyer
 */
public class MainActivity extends AppCompatActivity implements MainActivityInterface, AppBarLayout.OnOffsetChangedListener, Toolbar.OnMenuItemClickListener {
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
    private static final String mSocksServerPort = "8781";
    private static final String mDnsServerPort = "8753";

    private static final String FRONT = "front";
    private static final long TOOLBAR_ACC_ANIM_DURATION = 500;

    /**
     *
     */
    boolean isShow = false;
    int scrollRange = -1;

    private boolean mInternetDown = false;
    private long mInternetDownSince;
    private final BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                //action for connectivity
                boolean isNetworkConnected = ConnectionUtils.isNetworkConnected(context);
                if (!isNetworkConnected) {
                    TunnelManager manager = TunnelState.getTunnelState().getTunnelManager();
                    if (manager != null)
                        manager.stopSocksProxy();

                    mInternetDown = true;
                    mInternetDownSince = System.currentTimeMillis();
                    Log.d(TAG, "Stopping the socks proxy daemon due to no internet connection");
                } else if (mInternetDown) {
                    Log.d(TAG, "Resuming the socks proxy daemon now: " + mInternetDownSince + " ms passed");
                    TunnelManager manager = TunnelState.getTunnelState().getTunnelManager();
                    if (manager != null)
                        manager.runSocksProxy();

                    mInternetDown = false;
                    mInternetDownSince = 0;
                }
            }
        }
    };
    private Handler mUiHandler;
    private View mProgress;
    private Toolbar mToolbar;
    private Receiver vpnReceiver;

    private void bindActivity() {
        mProgress = findViewById(R.id.main_progress);
        mToolbar = (Toolbar) findViewById(R.id.main_toolbar);

        setSupportActionBar(mToolbar);

        mToolbar.inflateMenu(R.menu.menu_main);
        mToolbar.setOnMenuItemClickListener(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindActivity();

        if (findViewById(R.id.fragment_container) != null) {
            mUiHandler = new Handler();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST);
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_BROADCAST);
        filter.addAction(TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA);
        vpnReceiver = new Receiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnReceiver, filter);

        // Register a broadcast networkStateReceiver for network connection status changes
        IntentFilter connFilter = new IntentFilter();
        connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);

        registerReceiver(networkStateReceiver, connFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (AccountUtils.isSignedIn(this)) {
            showFeatureFragment();
        } else {
            showLoginFragment();
        }

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
    public boolean onCreateOptionsMenu(Menu menu) {
        if (AccountUtils.isSignedIn(this)) {
            getMenuInflater().inflate(R.menu.menu_feature, menu);
        } else {
            getMenuInflater().inflate(R.menu.menu_main, menu);
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver);
        unregisterReceiver(networkStateReceiver);
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
        if (isServiceRunning()) {
            Log.d(TAG, "already running service");
            TunnelManager tunnelManager = TunnelState.getTunnelState().getTunnelManager();
            if (tunnelManager != null) {
                tunnelManager.restartTunnel(mSocksServerAddress, mSocksServerPort, mDnsServerPort);
            }
            return;
        }
        Intent startTunnelVpn = new Intent(context, TunnelVpnService.class);
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_ADDRESS_BASE, mSocksServerAddress);
        startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_PORT_EXTRA, mSocksServerPort);
        startTunnelVpn.putExtra(TunnelManager.DNS_SERVER_PORT_EXTRA, mDnsServerPort);
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
        TunnelManager currentTunnelManager = TunnelState.getTunnelState().getTunnelManager();
        if (currentTunnelManager != null) {
            currentTunnelManager.signalStopService();
        }
        stopUi();
    }

    @Override
    public void signIn(String username, String password) {
        spEditString(SP_USERNAME, username);
        spEditString(SP_PASSWORD, password);
        spEditBoolean(IS_SIGNED_IN, true);

        showFeatureFragment();
        startVpn();
    }

    @Override
    public void signOut() {
        if (isServiceRunning())
            stopVpn();

        spEditString(SP_USERNAME, "");
        spEditString(SP_PASSWORD, "");
        spEditBoolean(IS_SIGNED_IN, false);

        showLoginFragment();
    }

    @Override
    public void showProgressBar() {
        mProgress.setVisibility(View.VISIBLE);
    }

    @Override
    public void dismissProgressBar() {
        mProgress.setVisibility(View.GONE);
    }

    @Override
    public void finishRegistration() {
        showLoginFragment();

        Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.registration_success_message),
                Snackbar.LENGTH_LONG
        ).show();
    }

    /**
     * Start the UI as the VPN service starts
     */
    private void startUi() {
        spEditBoolean(SP_SERVICE_HALT, false);
        try {
            ((SimpleUiControl) getSupportFragmentManager().findFragmentByTag(FRONT)).startUi();
        } catch (Exception e) {
            throw e;
        }

        Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.geph_service_started),
                Snackbar.LENGTH_LONG
        ).show();
    }

    /**
     * Stop the UI as the VPN service stops
     */
    private void stopUi() {
        spEditBoolean(SP_SERVICE_HALT, true);
        try {
            ((SimpleUiControl) getSupportFragmentManager().findFragmentByTag(FRONT)).stopUi();
        } catch (Exception e) {
            throw e;
        }

        Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.geph_service_stopped),
                Snackbar.LENGTH_LONG
        ).show();
    }

    /**
     * @return true iff there is a fragment attached to the main content; false otherwise
     */
    private boolean isContentFragmentAdded() {
        return getContentFragment() != null && getContentFragment().isAdded();
    }

    public void createNewAccount(View view) {

        final Proxbinder proxbinder = ProxbinderFactory.getProxbinder(getApplicationContext());
        if (proxbinder == null) {
            Log.e(TAG, "cannot start proxbinder");
        }

        showProgressBar();
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                new AsyncTask<Void, Void, Captcha>() {
                    @Override
                    protected Captcha doInBackground(Void... voids) {
                        try {
                            return proxbinder.getService().getFreshCaptcha().execute().body();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Captcha captcha) {
                        dismissProgressBar();
                        if (captcha == null) {
                            // handle error appropriately
                        } else {
                            // start a new fragment/activity for registration process
                            Bundle bundle = new Bundle();
                            bundle.putString(CAPTCHA_ID_EXTRA, captcha.getCaptchaId());
                            bundle.putString(CAPTCHA_IMAGE_EXTRA, captcha.getCaptchaImg());

                            RegistrationFragment registrationFragment = new RegistrationFragment();

                            registrationFragment.setArguments(bundle);

                            getSupportFragmentManager().beginTransaction()
                                    .replace(R.id.fragment_container, registrationFragment, FRONT)
                                    .addToBackStack("login")
                                    .commit();
                        }
                        proxbinder.close();
                    }
                }.execute();

            }
        });
    }

    /**
     * Prepare and setup the feature screen (i.e. a set of views for a logged in user).
     */
    private void showFeatureFragment() {
        mToolbar.getMenu().clear();
        mToolbar.inflateMenu(R.menu.menu_feature);

        FeatureFragment featureFragment = new FeatureFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, featureFragment, FRONT)
                .disallowAddToBackStack()
                .commitAllowingStateLoss();
    }

    /**
     * Prepare and setup the login screen
     */
    private void showLoginFragment() {
        mToolbar.getMenu().clear();
        mToolbar.inflateMenu(R.menu.menu_main);

        LoginFragment loginFragment = new LoginFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, loginFragment, FRONT)
                .disallowAddToBackStack()
                .commitAllowingStateLoss();
        // TODO (bunsim): changed commit to commitAllowingStateLoss but why??
        // This fixes "can not perform this action after onSaveInstanceState"
    }


    /*
        -----------------------------------------------------------------------
        TODO factor out the following three methods
     */

    private SharedPreferences.Editor getPrefEditor() {
        return getSharedPreferences(PREFS, 0).edit();
    }

    private void spEditString(String key, String value) {
        getPrefEditor()
                .putString(key, value)
                .commit();
    }

    private void spEditBoolean(String key, boolean value) {
        SharedPreferences.Editor editor = getPrefEditor();
        editor.putBoolean(key, value);
        editor.commit();
    }

    /*
        -----------------------------------------------------------------------
     */

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        if (scrollRange == -1) {
            scrollRange = appBarLayout.getTotalScrollRange();
        }
        if (scrollRange + verticalOffset == 0) {
            isShow = true;
        } else if (isShow) {
            isShow = false;
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.sign_out:
                signOut();
                break;
            case R.id.about:
                AboutDialogFragment dialog = new AboutDialogFragment();
                dialog.show(getSupportFragmentManager(), "about");
                break;
            case R.id.settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivityForResult(intent, REQUEST_CODE_SETTINGS);
                break;
        }
        return true;
    }

    /**
     * A simple broadcast receiver for receiving messages from TunnelVpnService
     */
    private class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST:
                    if (isContentFragmentAdded()) {
                        stopUi();

                        if (intent.getBooleanExtra(
                                TunnelVpnService.TUNNEL_VPN_STOP_INVALID_CREDENTIAL,
                                false
                        )) {
                            signOut();
                            new InvalidCredentialDialogFragment()
                                    .show(getSupportFragmentManager(), "invalid_user_credential");
                        }
                    }
                    break;
                case TunnelVpnService.TUNNEL_VPN_START_BROADCAST:
                    startUi();
                    break;
                case TunnelVpnService.TUNNEL_VPN_START_SUCCESS_EXTRA:
                    Log.d(TAG, "broadcast networkStateReceiver vpn start success extra");
                    break;
            }
        }
    }
}
