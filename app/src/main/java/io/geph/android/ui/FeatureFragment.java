package io.geph.android.ui;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.util.Timer;
import java.util.TimerTask;

import io.geph.android.AccountUtils;
import io.geph.android.ConnectionUtils;
import io.geph.android.Constants;
import io.geph.android.MainActivityInterface;
import io.geph.android.R;
import io.geph.android.tun2socks.TunnelState;

/**
 * A placeholder fragment containing a simple view.
 * @author j3sawyer
 */
public class FeatureFragment extends Fragment implements SimpleUiControl {
    private static final String TAG = FeatureFragment.class.getSimpleName();

    private static Timer mTimer;
    //
    private ToggleButton mVpnToggle;
    private View mStatusFragmentContainer;
    private View mMessageContainer;
    private View mMsgConnecting;
    private View mMsgConnected;
    private View mNoticeContainer;
    //
    private MainActivityInterface mListener;
    //
    private View.OnClickListener mVpnToggleClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            ToggleButton vpnToggle = (ToggleButton) view;

            if (vpnToggle.isChecked()) {
                Context context = getContext();
                if (ConnectionUtils.isNetworkConnected(context)) {
                    mListener.startVpn();
                } else {
                    Toast.makeText(context, getString(R.string.no_internet_connection), Toast.LENGTH_SHORT).show();
                    vpnToggle.setChecked(false);
                }
            } else {
                mListener.stopVpn();
            }
        }
    };

    public FeatureFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View frag = inflater.inflate(R.layout.fragment_feature, container, false);

        mVpnToggle = (ToggleButton) frag.findViewById(R.id.vpn_toggle);
        mVpnToggle.setOnClickListener(mVpnToggleClickListener);

        mStatusFragmentContainer = frag.findViewById(R.id.status_fragment_container);
        mStatusFragmentContainer.setAlpha(0f);

        mNoticeContainer = frag.findViewById(R.id.notice_container);

        mMessageContainer = frag.findViewById(R.id.message_container);
        mMsgConnecting = mMessageContainer.findViewById(R.id.connecting_message);
        mMsgConnected = mMessageContainer.findViewById(R.id.connected_message);
        mMessageContainer.setAlpha(0f);

        return frag;
    }

    private StatusInterface getStatusListInterface() {
        return (StatusInterface) getChildFragmentManager().findFragmentById(R.id.status_fragment);
    }

    private boolean isServiceHalt() {
        return getActivity()
                .getSharedPreferences(Constants.PREFS, 0)
                .getBoolean(Constants.SP_SERVICE_HALT, true);
    }

    @Override
    public void onResume() {
        super.onResume();
        String username = AccountUtils.getUsername(getContext());
        if (username == null || username.isEmpty()) {
            ((MainActivityInterface) getActivity()).signOut();
            return;
        }
        startUi();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (isServiceRunning()) {
            stopUi();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mListener = (MainActivityInterface) context;
        } catch (ClassCastException e) {
            Log.e(TAG, "The parent activity does not implement MainActivityInterface: "
                    + e.getMessage());
        }
    }

    @Override
    public synchronized void startUi() {
        if (isServiceRunning() && !isServiceHalt()) {
            toggleOn();

            mNoticeContainer.animate().alpha(0f).setDuration(200);

            showFragment();
            changeMessage("connecting");
            mMessageContainer.animate().alpha(1.0f).setDuration(1000);
            if (mTimer != null) {
                mTimer.cancel();
                mTimer = null;
            }
            mTimer = new Timer();
            updateStatus();
        }
    }

    private void showFragment() {
        mStatusFragmentContainer.animate().alpha(1.0f).setDuration(1000);
    }

    private void hideFragment() {
        mStatusFragmentContainer.animate().alpha(0f).setDuration(200);
    }

    @Override
    public void stopUi() {
        Log.d(TAG, "stop updating UIs");

        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
        mMessageContainer.animate().alpha(0f).setDuration(200);
        hideFragment();
        toggleOff();

        mNoticeContainer.animate().alpha(1.0f).setDuration(1000);

        StatusInterface sli = getStatusListInterface();
        if (sli != null) sli.reset();
    }

    @Override
    public void notifyStatus(String status) {
        // TODO separate this logic into an isolated fragment
        changeMessage(status);
    }

    private void changeMessage(String status) {
        if (status.equalsIgnoreCase("connected")) {
            if (mMsgConnected.getVisibility() == View.GONE) {
                mMsgConnected.setVisibility(View.VISIBLE);
                mMsgConnecting.setVisibility(View.GONE);
            }
        } else {
            if (mMsgConnecting.getVisibility() == View.GONE) {
                mMsgConnecting.setVisibility(View.VISIBLE);
                mMsgConnected.setVisibility(View.GONE);
            }
        }
    }

    protected boolean isServiceRunning() {
        TunnelState tunnelState = TunnelState.getTunnelState();
        return tunnelState.getStartingTunnelManager() || tunnelState.getTunnelManager() != null;
    }

    private void toggleOn() {
        mVpnToggle.setChecked(true);
    }

    private void toggleOff() {
        mVpnToggle.setChecked(false);
    }

    private void updateStatus() {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                if (!isServiceRunning()) cancel();
                StatusInterface sli = getStatusListInterface();
                if (sli != null) sli.invalidate();
            }
        };
        mTimer.schedule(timerTask, 0, 1000);
    }
}
