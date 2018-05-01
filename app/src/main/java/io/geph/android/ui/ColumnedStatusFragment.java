package io.geph.android.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.gson.JsonObject;

import io.geph.android.Constants;
import io.geph.android.R;
import io.geph.android.api.GephServiceHelper;
import io.geph.android.api.SimpleTask;
import io.geph.android.api.models.AccountInfo;
import io.geph.android.api.models.NetInfo;
import io.geph.android.api.models.Summary;
import io.geph.android.api.tasks.GetAccountInfoTask;
import io.geph.android.api.tasks.GetNetInfoTask;
import io.geph.android.api.tasks.GetSummaryTask;

/**
 * Displaying connection status in two columns.
 * <p>
 * Created by j3sawyer on 2017-01-23.
 */
public class ColumnedStatusFragment extends Fragment implements StatusInterface {

    private static final String TAG = ColumnedStatusFragment.class.getSimpleName();
    private static final int BYTE_BASE = Constants.BYTE_BASE;
    private static final String[] NET_SPEED_UNITS = new String[]{"KB/s", "MB/s", "GB/s"};
    private static final String[] BALANCE_UNITS = new String[]{"MB", "GB", "TB"};
    private static final long mBalanceRefreshEpoch = 10 * 1000;
    private TextView mPubIp;
    private TextView mConnection;
    private TextView mBalance;
    private TextView mPlan;
    private Handler mUiHandler;
    private long mLastBytesRx = 0;
    private long mLastBytesTx = 0;
    private long mLastUpdated;
    private long mBalanceUpdated = 0;
    /**
     * A task to get Geph summary
     */
    private GetSummaryTask mGetSummaryTask = new GetSummaryTask(TAG);
    /**
     * Periodical task to perform on receiving Geph summary
     */
    private SimpleTask<Summary> mOnFetchingNewSummary = new SimpleTask<Summary>() {
        @Override
        public void doTask(Summary summary) {
            if (summary != null) {
                try {
                    ((SimpleUiControl) getParentFragment()).notifyStatus(summary.getStatus());

                    if (summary.getStatus().equalsIgnoreCase(Summary.STATUS_CONNECTED)) {
                        GephServiceHelper.callAsync(
                                2000,
                                new GetNetInfoTask(),
                                new SimpleTask<NetInfo>() {
                                    @Override
                                    public void doTask(NetInfo arg) {
                                        if (arg != null) {
                                            refresh(mPubIp, arg.getExit());
                                            if (arg.getEntry().isEmpty()) {
                                                renderConnStatus("backup");
                                            } else {
                                                renderConnStatus(arg.getEntry());
                                            }
                                        }
                                    }
                                }
                        );
                        final long now = System.currentTimeMillis();
                        if (mBalanceUpdated + mBalanceRefreshEpoch < now) {
                            GephServiceHelper.callAsync(
                                    2000,
                                    new GetAccountInfoTask(),
                                    new SimpleTask<AccountInfo>() {
                                        @Override
                                        public void doTask(AccountInfo arg) {
                                            if (arg != null) {
                                                try {
                                                    Log.d(TAG, "WANNA UPDATE!");
                                                    mBalanceUpdated = now;
                                                    JsonObject prinfo = arg.getPremiumInfo();
                                                    if (prinfo.getAsJsonPrimitive("Plan").getAsString().equals("")) {
                                                        Log.d(TAG, "UPDATE AS FREE");
                                                        refresh(mBalance, formatBalance(arg.getBalance()));
                                                        refresh(mPlan, getString(R.string.free));
                                                    } else {
                                                        Log.d(TAG, "UPDATE AS PAID");
                                                        JsonObject desc = prinfo.getAsJsonObject("Desc");
                                                        refresh(mPlan, desc.getAsJsonObject("Name")
                                                                .getAsJsonPrimitive("en").getAsString());
                                                        if (prinfo.getAsJsonPrimitive("Unlimited").getAsBoolean()) {
                                                            refresh(mBalance, getString(R.string.unlimited));
                                                        } else {
                                                            refresh(mBalance, formatBalance(arg.getBalance()));
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    Log.d(TAG, "UPDATE ERROR " + e.toString());
                                                }
                                            }
                                        }
                                    }
                            );
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            } else {
                // Initializing
                Log.d(TAG, "initializing...");
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        SharedPreferences sp = getContext().getSharedPreferences(Constants.PREFS, 0);

        mLastBytesTx = sp.getLong(Constants.SP_LAST_BYTES_TX, 0);
        mLastBytesRx = sp.getLong(Constants.SP_LAST_BYTES_RX, 0);
        mLastUpdated = sp.getLong(Constants.SP_LAST_UPDATED, 0);
    }

    @Override
    public void onPause() {
        super.onPause();
        SharedPreferences sp = getContext().getSharedPreferences(Constants.PREFS, 0);
        SharedPreferences.Editor editor = sp.edit();

        editor.putLong(Constants.SP_LAST_BYTES_TX, mLastBytesTx);
        editor.putLong(Constants.SP_LAST_BYTES_RX, mLastBytesRx);
        editor.putLong(Constants.SP_LAST_UPDATED, mLastUpdated);

        editor.commit();
    }

    /**
     * Simple helper
     *
     * @param textView a textview to display the given value
     * @param value    a value to display
     */
    private void refresh(TextView textView, String value) {
        refresh(textView, value, R.color.black_light);
    }

    private void refresh(TextView textView, String value, int colorId) {
        textView.setText(value);
        textView.setTextColor(ContextCompat.getColor(getContext(), colorId));
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_columned_status, container, false);

        mPubIp = (TextView) v.findViewById(R.id.status_public_ip);
        mConnection = (TextView) v.findViewById(R.id.status_connection);
        mPlan = (TextView) v.findViewById(R.id.status_plan);
        mBalance = (TextView) v.findViewById(R.id.status_balance);

        renderConnStatus("connecting");

        mUiHandler = new Handler();

        return v;
    }

    @Override
    public void invalidate() {
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                GephServiceHelper.callAsync(
                        200,
                        mGetSummaryTask,
                        mOnFetchingNewSummary
                );
            }
        });
    }

    @Override
    public void reset() {
        // reset text fields if desired
    }

    /**
     * @param balance a string representing the current balance left
     * @return a formatted string with appropriate data unit
     */
    private String formatBalance(String balance) {
        Integer intBalance;
        try {
            intBalance = Integer.parseInt(balance);
        } catch (NumberFormatException e) {
            return "Invalid Balance";
        }

        double adjusted = intBalance;
        int pos = 0;
        while (adjusted >= BYTE_BASE) {
            adjusted = adjusted / BYTE_BASE;
            pos++;
        }
        return ((long) adjusted) + " " + BALANCE_UNITS[pos];
    }

    /**
     * Note that for 'Normal' condition (i.e. `status` is a non-empty string which represents
     * an entry address), the first six HEX digits of the address should also be displayed
     * followed by the status string (e.g. Normal (BEEF00) ).
     * <p>
     * Display a formatted connection status with a proper color id
     *
     * @param status a status string or an entry address
     */
    private void renderConnStatus(String status) {
        int resId;
        int colorId;
        switch (status) {
            case "connected":
            case "connecting":
                resId = R.string.connecting;
                colorId = R.color.black_light;
                break;
            case "backup":
                resId = R.string.backup;
                colorId = R.color.red;
                break;
            default:
                resId = R.string.normal;
                colorId = R.color.green;
        }

        String ret = getString(resId);
        if (resId == R.string.normal) {
            ret += " (" + status.substring(0, 6) + ")";
        }

        refresh(mConnection, ret, colorId);
    }

    /**
     * @param bytes    amount of data transferred in bytes
     * @param duration duration of data transfer
     * @return a formatted string with proper units in [K,M,G]B/s
     */
    private String formatSpeed(long bytes, int duration) {
        double adjusted = bytes * 1000 / BYTE_BASE;
        adjusted /= duration;
        int pos = 0;
        while (adjusted >= BYTE_BASE) {
            adjusted = adjusted / BYTE_BASE;
            pos++;
        }
        return ((long) adjusted) + " " + NET_SPEED_UNITS[pos];
    }
}
