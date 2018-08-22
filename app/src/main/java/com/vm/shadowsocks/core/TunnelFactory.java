package com.vm.shadowsocks.core;

import android.util.Log;

import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tunnel.Config;
import com.vm.shadowsocks.tunnel.TcpRawTunnel;
import com.vm.shadowsocks.tunnel.TcpTunnel;
import com.vm.shadowsocks.tunnel.TcpBaseTunnel;
import com.vm.shadowsocks.tunnel.UdpBaseTunnel;
import com.vm.shadowsocks.tunnel.UdpRawTunnel;
import com.vm.shadowsocks.tunnel.UdpTunnel;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;

public class TunnelFactory {

    private static final String TAG = TunnelFactory.class.getSimpleName();

    public static TcpBaseTunnel wrap(SocketChannel channel, Selector selector) {
        return new TcpRawTunnel(channel, selector);
    }

    public static UdpRawTunnel wrap(DatagramChannel channel, Selector selector) {
        return new UdpRawTunnel(channel, selector);
    }

    protected static InetSocketAddress getDestAddress(AbstractSelectableChannel localChannel) throws Exception {
        int portKey;
        InetAddress desAddr;
        if (localChannel instanceof SocketChannel) {
            portKey = ((SocketChannel) localChannel).socket().getPort();
            desAddr = ((SocketChannel) localChannel).socket().getInetAddress();
        } else if (localChannel instanceof DatagramChannel) {
            portKey = ((DatagramChannel) localChannel).socket().getPort();
            desAddr = ((DatagramChannel) localChannel).socket().getInetAddress();
        } else {
            throw new Exception("unsupported channel type:" + localChannel.getClass().getName());
        }
        NatSession session = NatSessionManager.getSession(portKey);
        if (session != null) {
            if (ProxyConfig.Instance.needProxy(session.RemoteHost, session.RemoteIP)) {
                Log.d(TAG, String.format("%d/%d:[PROXY] %s=>%s:%d\n", NatSessionManager.getSessionCount(), TcpBaseTunnel.SessionCount, session.RemoteHost, CommonMethods.ipIntToString(session.RemoteIP), session.RemotePort & 0xFFFF));
                if (session.RemoteHost != null) {
                    return new InetSocketAddress(session.RemoteHost, session.RemotePort);
                } else {
                    return new InetSocketAddress(CommonMethods.ipIntToInet4Address(session.RemoteIP), session.RemotePort);
                }
            } else {
                return new InetSocketAddress(desAddr, session.RemotePort & 0xFFFF);
            }
        }
        return null;
    }
}
