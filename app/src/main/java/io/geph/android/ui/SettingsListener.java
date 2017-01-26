package io.geph.android.ui;

/**
 * Created by j3sawyer on 2017-01-18.
 */

public interface SettingsListener {
    void notifyRestartRequired(String changedSettingKey, Object changedSettingValue);
}
