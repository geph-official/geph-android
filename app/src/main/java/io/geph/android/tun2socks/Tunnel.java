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
    private final HostService mHostService;
    private PrivateAddress mPrivateAddress;
    private AtomicReference<ParcelFileDescriptor> mTunFd;
    private AtomicBoolean mRoutingThroughTunnel;
    private Thread mTun2SocksThread;

    private Tunnel(HostService hostService) {
        mHostService = hostService;
        mTunFd = new AtomicReference<>();
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

    private static PrivateAddress selectPrivateAddress() throws Exception {
        // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
        // which private address range isn't in use.
        Map<String, PrivateAddress> candidates = new HashMap<String, PrivateAddress>();
        candidates.put("10", new PrivateAddress("10.0.0.1", "10.0.0.0", 8, "10.0.0.2"));
        candidates.put("172", new PrivateAddress("172.16.0.1", "172.16.0.0", 12, "172.16.0.2"));
        candidates.put("192", new PrivateAddress("192.168.0.1", "192.168.0.0", 16, "192.168.0.2"));
        candidates.put("169", new PrivateAddress("169.254.1.1", "169.254.1.0", 24, "169.254.1.2"));

        List<NetworkInterface> netInterfaces;
        try {
            netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            e.printStackTrace();
            throw new Exception("selectPrivateAddress failed", e);
        }

        for (NetworkInterface netInterface : netInterfaces) {
            for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses())) {

                if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                    String ipAddress = inetAddress.getHostAddress();
                    if (ipAddress.startsWith("10.")) {
                        candidates.remove("10");
                    } else if (ipAddress.length() >= 6
                            && ipAddress.substring(0, 6).compareTo("172.16") >= 0
                            && ipAddress.substring(0, 6).compareTo("172.31") <= 0) {
                        candidates.remove("172");
                    } else if (ipAddress.startsWith("192.168")) {
                        candidates.remove("192");
                    }
                }
            }
        }

        if (candidates.size() > 0) {
            return candidates.values().iterator().next();
        }

        throw new Exception("no private address available");
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
        mPrivateAddress = selectPrivateAddress();

        Locale previousLocale = Locale.getDefault();

        final String errorMessage = "startVpn failed";
        try {
            // Workaround for https://code.google.com/p/android/issues/detail?id=61096
            Locale.setDefault(new Locale("en"));

            ParcelFileDescriptor tunFd = null;
            try {
                tunFd =
                        ((VpnService.Builder) mHostService.newVpnServiceBuilder())
                                .setSession(mHostService.getAppName())
                                .setMtu(VPN_INTERFACE_MTU)
                                .addAddress(mPrivateAddress.mIpAddress, mPrivateAddress.mPrefixLength)
//                                .addAddress("1001::", mPrivateAddress.mPrefixLength)
//                                .addRoute("0000::", 0)
                                .addRoute("0.0.0.0", 0)
                                .addDnsServer(mPrivateAddress.mRouter)
//                                .addDnsServer("1002::")
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
            mTunFd.set(tunFd);
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
        ParcelFileDescriptor tunFd = mTunFd.get();
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

    private synchronized void stopRoutingThroughTunnel() {
        stopTun2Socks();
    }

    private synchronized  void stopVpn() {
        stopTun2Socks();
        ParcelFileDescriptor tunFd = mTunFd.getAndSet(null);
        if (tunFd != null) {
            try {
                mHostService.onDiagnosticMessage("closing VPN interface");
                tunFd.close();
            } catch (IOException e) {
            }
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
                                Tun2SocksJni.runTun2Socks(
                                        vpnInterfaceFileDescriptor.getFd(),
                                        vpnInterfaceMTU,
                                        vpnIpAddress,
                                        vpnNetMask,
                                        socksServerAddress,
                                        dnsServerAddress,
                                        transparentDns ? 1 : 0);
                            }
                        });
        mTun2SocksThread.start();
        mHostService.onDiagnosticMessage("tun2socks started");
    }

    private void stopTun2Socks() {
        if (mTun2SocksThread != null) {
            try {
                Tun2SocksJni.terminateTun2Socks();
                Thread.sleep(200);
                mTun2SocksThread.interrupt();
                mTun2SocksThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

        // Object must be a VpnService; Android < 4 cannot reference this class name
        public Object getVpnService();

        // Object must be a VpnService.Builder;
        // Android < 4 cannot reference this class name
        public Object newVpnServiceBuilder();

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