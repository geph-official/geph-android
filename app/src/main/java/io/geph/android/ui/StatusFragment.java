package io.geph.android.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

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
 * @author j3sawyer
 */
@Deprecated
public class StatusFragment extends Fragment implements StatusInterface {
    private static final String TAG = StatusFragment.class.getSimpleName();

    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private SimpleStringRecyclerViewAdapter mAdapter;
    private Handler mUiHandler;

    private Map<Integer, StatusItem> mItems;

    private long mLastBytesRx = 0;
    private long mLastBytesTx = 0;
    private long mBalanceUpdated = 0;
    private final long mBalanceRefreshEpoch = 30 * 1000;

    private GetSummaryTask mGetSummaryTask = new GetSummaryTask(TAG);
    private SimpleTask<Summary> mOnFetchingNewSummary = new SimpleTask<Summary>() {
        @Override
        public void doTask(Summary summary) {
            if (summary != null) {
                try {
                    refresh(StatusItem.POS_CONN, formatConn(summary.getStatus()), true);
                    refresh(StatusItem.POS_UPTIME, formatUptime(summary.getUptime()), true);

                    long rx = summary.getBytesRx();
                    if (mLastBytesRx > 0) {
                        refresh(StatusItem.POS_DOWNLOAD, formatSpeed(rx - mLastBytesRx), true);
                    }
                    mLastBytesRx = rx;

                    long tx = summary.getBytesTx();
                    if (mLastBytesTx > 0) {
                        refresh(StatusItem.POS_UPLOAD, formatSpeed(tx - mLastBytesTx), true);
                    }
                    mLastBytesTx = tx;

                    mAdapter.notifyDataSetChanged();

                    if (summary.getStatus().equalsIgnoreCase(Summary.STATUS_CONNECTED)) {
                        if (!mItems.get(StatusItem.POS_PUB_IP).isInitialized()) {
                            GephServiceHelper.callAsync(
                                    2000,
                                    new GetNetInfoTask(),
                                    new SimpleTask<NetInfo>() {
                                        @Override
                                        public void doTask(NetInfo arg) {
                                            refresh(StatusItem.POS_PUB_IP, arg.getExit());
                                        }
                                    }
                            );
                        }
                        final long now = System.currentTimeMillis();
                        if (mBalanceUpdated + mBalanceRefreshEpoch < now) {
                            GephServiceHelper.callAsync(
                                    2000,
                                    new GetAccountInfoTask(),
                                    new SimpleTask<AccountInfo>() {
                                        @Override
                                        public void doTask(AccountInfo arg) {
                                            refresh(StatusItem.POS_REM_BAL, formatBalance(arg.getBalance()));
                                            mBalanceUpdated = now;
                                        }
                                    }
                            );
                        }
                    } else if (summary.getStatus().equalsIgnoreCase(Summary.STATUS_DISCONNECTED)) {
                        mItems.get(StatusItem.POS_PUB_IP).setInitialized(false);
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

    private String formatBalance(String balance) {
        return balance + " MB";
    }

    private String formatConn(String status) {
        return status.substring(0, 1).toUpperCase() + status.substring(1);
    }

    private String formatSpeed(long bytes) {
        return (bytes / 1024) + " KB/s";
    }

    private String formatUptime(long uptime) {
        return uptime + " s";
    }

    private void refresh(int key, String value) {
        refresh(key, value, false);
    }

    private void refresh(int key, String value, boolean deferNotifying) {
        if (mItems.containsKey(key)) {
            mItems.get(key).setBody(value);
        }
        if (!deferNotifying)
            mAdapter.notifyDataSetChanged();
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, container, false);

        mRecyclerView = (RecyclerView) v.findViewById(R.id.recyclerview);

        // use this setting to improve performance if you know that changes
        // in content do not change the layout size of the RecyclerView
        mRecyclerView.setHasFixedSize(true);

        // use a linear layout manager
        mLayoutManager = new LinearLayoutManager(getActivity());
        mRecyclerView.setLayoutManager(mLayoutManager);

        // specify an adapter (see also next example)
        mItems = new HashMap<>();
        mAdapter = new SimpleStringRecyclerViewAdapter(getContext(), mItems);
        mRecyclerView.setAdapter(mAdapter);

        mUiHandler = new Handler();

        return v;
    }

    @Override
    public void invalidate() {
        if (mItems.isEmpty()) {
            StatusItem.initStatusMap(mItems);
        }
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
        mItems.clear();
        mAdapter.notifyDataSetChanged();
    }

    public static class SimpleStringRecyclerViewAdapter
            extends RecyclerView.Adapter<SimpleStringRecyclerViewAdapter.ViewHolder> {

        private final TypedValue mTypedValue = new TypedValue();
        private int mBackground;
        private Map<Integer, StatusItem> mValues;

        public SimpleStringRecyclerViewAdapter(Context context, Map<Integer, StatusItem> items) {
            context.getTheme().resolveAttribute(R.attr.selectableItemBackground, mTypedValue, true);
            mBackground = mTypedValue.resourceId;
            mValues = items;
        }

        public StatusItem getValueAt(int position) {
            return mValues.get(position);
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.status_item, parent, false);
            view.setBackgroundResource(mBackground);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            StatusItem si = mValues.get(position);
            holder.mBoundString = si.toString();

            holder.mTitle.setText(si.getTitle());
            if (si.isInitialized())
                holder.mBody.setText(si.getBody());
            else
                holder.mBody.setText(si.getDefaultMessage());
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mTitle;
            public final TextView mBody;
            public String mBoundString;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mTitle = (TextView) view.findViewById(R.id.title);
                mBody = (TextView) view.findViewById(R.id.body);
            }

            @Override
            public String toString() {
                return super.toString() + " '" + mTitle.getText() + "' -> '" + mBody.getText();
            }
        }
    }
}
