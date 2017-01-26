package io.geph.android.ui;

/**
 * @author j3sawyer
 */
public interface SimpleUiControl {
    void startUi();
    void stopUi();
    void notifyStatus(String status);
}
