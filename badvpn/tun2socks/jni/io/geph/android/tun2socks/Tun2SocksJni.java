package io.geph.android.tun2socks;

import android.util.Log;

/**
 * @author j3sawyer
 */

public class Tun2SocksJni {

    static {
        System.loadLibrary("tun2socks");
    }

    public static native int runTun2Socks(
            int vpnInterfaceFileDescriptor,
            int vpnInterfaceMTU,
            String vpnIpAddress,
            String vpnNetMask,
            String socksServerAddress,
            String dnsServerAddress,
            int transparentDNS);

    public static native int terminateTun2Socks();

    public static void logTun2Socks(String level, String channel, String msg) {
        String logMsg = String.format("%s (%s): %s", level, channel, msg);
        Log.i("Bridge", logMsg);
    }
}
