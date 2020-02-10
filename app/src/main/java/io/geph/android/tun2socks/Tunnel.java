package io.geph.android.tun2socks;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.util.Log;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.spec.ECField;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Tunnel {

    private static final String VPN_INTERFACE_NETMASK = "255.255.255.0";
    private static final int VPN_INTERFACE_MTU = 1500;
    // Only one VpnService instance may exist at a time, as the underlying
    // tun2socks implementation contains global state.
    private static Tunnel mTunnel;
    private ParcelFileDescriptor tunFd;
    private final HostService mHostService;
    private PrivateAddress mPrivateAddress;
    private AtomicReference<ParcelFileDescriptor> mTunFd;
    private AtomicBoolean mRoutingThroughTunnel;
    private Thread mTun2SocksThread;

    private Tunnel(HostService hostService) {
        mHostService = hostService;
        mRoutingThroughTunnel = new AtomicBoolean(false);
    }

    public static synchronized Tunnel newTunnel(HostService hostService) {
        if (mTunnel != null) {
            mTunnel.stop();
        }
        mTunnel = new Tunnel(hostService);
        return mTunnel;
    }

    //----------------------------------------------------------------------------------------------
    // Public API
    //----------------------------------------------------------------------------------------------

    // To startUi, call in sequence: startRouting(), then startTunneling(). After startRouting()
    // succeeds, the caller must call stopUi() to clean up.

    private static PrivateAddress selectPrivateAddress() {
        return new PrivateAddress("10.81.4.1", "10.81.4.0", 24, "10.81.4.2");
    }

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    // Returns true when the VPN routing is established; returns false if the VPN could not
    // be started due to lack of prepare or revoked permissions (called should re-prepare and
    // try again); throws exception for other error conditions.
    public synchronized boolean startRouting() throws Exception {
        return startVpn();
    }

    // Starts tun2socks. Returns true on success.
    public synchronized boolean startTunneling(String socksServerAddress, String dnsServerAddress)
            throws Exception {
        return routeThroughTunnel(socksServerAddress, dnsServerAddress);
    }

    //----------------------------------------------------------------------------
    // VPN Routing
    //----------------------------------------------------------------------------

    // Stops routing traffic through the tunnel by stopping tun2socks.
    // The VPN is unaffected by this method.
    public synchronized void stopTunneling() {
        stopRoutingThroughTunnel();
    }

    // Note: to avoid deadlock, do not call directly from a HostService callback;
    // instead post to a Handler if necessary to trigger from a HostService callback.
    public synchronized void stop() {
        stopVpn();
    }

    // Note: Atomic variables used for getting/setting local proxy port, routing flag, and
    // tun fd, as these functions may be called via callbacks. Do not use
    // synchronized functions as stopUi() is synchronized and a deadlock is possible as callbacks
    // can be called while stopUi holds the lock.
    //
    // Calling addDisallowedApplication on VPNService.Builder requires API 21 (Lollipop).
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private synchronized boolean startVpn() throws Exception {
        mHostService.onDiagnosticMessage("startVpn()");
        mPrivateAddress = selectPrivateAddress();

        Locale previousLocale = Locale.getDefault();

        final String errorMessage = "startVpn failed";
        try {
            // Workaround for https://code.google.com/p/android/issues/detail?id=61096
            Locale.setDefault(new Locale("en"));

            tunFd = null;
            try {
                tunFd =
                        ((VpnService.Builder) mHostService.newVpnServiceBuilder())
                                .setSession(mHostService.getAppName())
                                .setMtu(VPN_INTERFACE_MTU)
                                .addAddress(mPrivateAddress.mIpAddress, mPrivateAddress.mPrefixLength)
                                .addRoute("0.0.0.0", 0)
                                .addDnsServer(mPrivateAddress.mRouter)
                                .addDisallowedApplication(mHostService.getContext().getPackageName())
                                .establish();
            } catch (PackageManager.NameNotFoundException e) {
                mHostService.onDiagnosticMessage(
                        "failed exclude app from VPN: " + e.getMessage());
            }

            if (tunFd == null) {
                // As per http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29,
                // this application is no longer prepared or was revoked.
                return false;
            }
            mRoutingThroughTunnel.set(false);
            mHostService.onVpnEstablished();

        } catch (IllegalArgumentException e) {
            throw new Exception(errorMessage, e);
        } catch (SecurityException e) {
            throw new Exception(errorMessage, e);
        } catch (IllegalStateException e) {
            throw new Exception(errorMessage, e);
        } finally {
            // Restore the original locale.
            Locale.setDefault(previousLocale);
        }

        return true;
    }

    private synchronized  boolean routeThroughTunnel(String socksServerAddress, String dnsServerAddress) {
        if (!mRoutingThroughTunnel.compareAndSet(false, true)) {
            return false;
        }
        if (tunFd == null) {
            return false;
        }

        startTun2Socks(
                tunFd,
                VPN_INTERFACE_MTU,
                mPrivateAddress.mRouter,
                VPN_INTERFACE_NETMASK,
                socksServerAddress,
                dnsServerAddress,
                true /* transparent DNS */);

        mHostService.onTunnelConnected();
        mHostService.onDiagnosticMessage("routing through tunnel");

        // TODO: should double-check tunnel routing; see:
        // https://bitbucket.org/psiphon/psiphon-circumvention-system/src/1dc5e4257dca99790109f3bf374e8ab3a0ead4d7/Android/PsiphonAndroidLibrary/src/com/psiphon3/psiphonlibrary/TunnelCore.java?at=default#cl-779
        return true;
    }

    private void stopRoutingThroughTunnel() {
        stopTun2Socks();
    }

    private synchronized void stopVpn() {
        stopTun2Socks();
        if (tunFd != null) {
            try {
                mHostService.onDiagnosticMessage("closing VPN interface");
                Thread.sleep(100);
                tunFd.close();
                tunFd = null;
            } catch (InterruptedException e) {
                System.exit(-1);
            } catch (IOException e) {
                System.exit(-1);
            }
        } else {
            mHostService.onDiagnosticMessage("tried to stop already stopped!");
        }
    }

    //----------------------------------------------------------------------------
    // Tun2Socks
    //----------------------------------------------------------------------------

    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void startTun2Socks(
            final ParcelFileDescriptor vpnInterfaceFileDescriptor,
            final int vpnInterfaceMTU,
            final String vpnIpAddress,
            final String vpnNetMask,
            final String socksServerAddress,
            final String dnsServerAddress,
            final boolean transparentDns) {
        if (mTun2SocksThread != null) {
            return;
        }
        mTun2SocksThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                Log.d("Tunnel", "vpnIpAddress = " + vpnIpAddress);
                                Log.d("Tunnel", "vpnNetMask = " + vpnNetMask);
                                Log.d("Tunnel", "socksServerAddress = " + socksServerAddress);
                                Log.d("Tunnel", "dnsServerAddress = " + dnsServerAddress);
                                Log.d("Tunnel", "FD = " + vpnInterfaceFileDescriptor.getFd());
                                Tun2SocksJni.runTun2Socks(
                                        vpnInterfaceFileDescriptor.getFd(),
                                        vpnInterfaceMTU,
                                        vpnIpAddress,
                                        vpnNetMask,
                                        socksServerAddress,
                                        dnsServerAddress,
                                        transparentDns ? 1 : 0);
                                Log.e("Tunnel", "runTun2Socks returned?!");
                            }
                        });
        mTun2SocksThread.start();
        mHostService.onDiagnosticMessage("tun2socks started");
    }

    private void stopTun2Socks() {
        if (mTun2SocksThread != null) {
            int rval = Tun2SocksJni.terminateTun2Socks();
            mHostService.onDiagnosticMessage("terminateTun2Socks() returned " + rval);
            try {
                mTun2SocksThread.join();
            }catch(InterruptedException e) {

            }
            mTun2SocksThread = null;
            mRoutingThroughTunnel.set(false);
            mHostService.onDiagnosticMessage("tun2socks stopped");
        }
    }

    //----------------------------------------------------------------------------
    // Implementation: Network Utils
    //----------------------------------------------------------------------------

    public interface HostService {
        public String getAppName();

        public Context getContext();

        // Object must be a VpnService
        public VpnService getVpnService();

        // Object must be a VpnService.Builder;
        public VpnService.Builder newVpnServiceBuilder();

        public void onDiagnosticMessage(String message);

        public void onTunnelConnected();

        public void onVpnEstablished();
    }

    private static class PrivateAddress {
        public final String mIpAddress;
        public final String mSubnet;
        public final int mPrefixLength;
        public final String mRouter;

        public PrivateAddress(String ipAddress, String subnet, int prefixLength, String router) {
            mIpAddress = ipAddress;
            mSubnet = subnet;
            mPrefixLength = prefixLength;
            mRouter = router;
        }
    }

    //----------------------------------------------------------------------------
    // Exception
    //----------------------------------------------------------------------------

    public static class Exception extends java.lang.Exception {
        private static final long serialVersionUID = 1L;

        public Exception(String message) {
            super(message);
        }

        public Exception(String message, Throwable cause) {
            super(message + ": " + cause.getMessage());
        }
    }
}