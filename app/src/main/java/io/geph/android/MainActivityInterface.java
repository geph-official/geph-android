package io.geph.android;

/**
 * @author j3sawyer
 */

public interface MainActivityInterface {
    void startVpn();
    void stopVpn();
    void signIn(String username, String password);
    void signOut();
    void showProgressBar();
    void dismissProgressBar();
    void finishRegistration();
}
