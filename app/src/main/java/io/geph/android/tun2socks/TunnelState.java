package io.geph.android.tun2socks;

public class TunnelState {

    private static TunnelState m_tunnelState;
    private TunnelManager m_tunnelManager = null;
    private boolean m_startingTunnelManager = false;

    private TunnelState() {
    }

    public static synchronized TunnelState getTunnelState() {
        if (m_tunnelState == null) {
            m_tunnelState = new TunnelState();
        }
        return m_tunnelState;
    }

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public synchronized TunnelManager getTunnelManager() {
        return m_tunnelManager;
    }

    public synchronized void setTunnelManager(TunnelManager tunnelManager) {
        m_tunnelManager = tunnelManager;
        m_startingTunnelManager = false;
    }

    public synchronized void setStartingTunnelManager() {
        m_startingTunnelManager = true;
    }

    public synchronized boolean getStartingTunnelManager() {
        return m_startingTunnelManager;
    }
}