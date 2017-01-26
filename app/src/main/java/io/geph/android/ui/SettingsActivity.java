package io.geph.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;

import java.util.HashMap;
import java.util.Map;

import io.geph.android.Constants;
import io.geph.android.R;

/**
 * Created by j3sawyer on 2017-01-18.
 */

public class SettingsActivity extends AppCompatActivity implements SettingsListener {
    private static final String TAG = SettingsActivity.class.getSimpleName();

    private Toolbar mToolbar;
    private Map<String, Object> changes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);

        mToolbar = (Toolbar) findViewById(R.id.settings_toolbar);
        setSupportActionBar(mToolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        changes = new HashMap<>();

        getFragmentManager().beginTransaction()
                .replace(R.id.content_frame, new SettingsFragment())
                .commit();
    }

    @Override
    public void notifyRestartRequired(String changedSettingKey, Object changedSettingValue) {
        if (changes.containsKey(changedSettingKey)) {
            changes.remove(changedSettingKey);
        } else {
            changes.put(changedSettingKey, changedSettingValue);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!changes.isEmpty()) {
            Intent intent = new Intent();
            intent.putExtra(Constants.RESTART_REQUIRED, true);
            setResult(RESULT_OK, intent);
        }
        super.onBackPressed();
    }
}
