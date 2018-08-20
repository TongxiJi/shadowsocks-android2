package com.vm.shadowsocks.core;

import android.util.Log;

import com.vm.shadowsocks.tunnel.Tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

public class UdpProxyServer implements Runnable {
    private static final String TAG = UdpProxyServer.class.getSimpleName();
    private static final int UDP_RECV_BUFF_SIZE = 64 * 1024;

    public boolean Stopped;
    public short Port;

    Selector m_Selector;
    DatagramChannel udpServerChannel;
    Thread m_ServerThread;

    public UdpProxyServer(int port) throws IOException {
        m_Selector = Selector.open();
        udpServerChannel = DatagramChannel.open();
        udpServerChannel.configureBlocking(false);
        udpServerChannel.socket().bind(new InetSocketAddress(port));
        udpServerChannel.register(m_Selector, SelectionKey.OP_READ);
        this.Port = (short) udpServerChannel.socket().getLocalPort();
        Log.d(TAG, String.format("AsyncUdpServer listen on %d success.\n", this.Port & 0xFFFF));
    }

    public void start() {
        m_ServerThread = new Thread(this);
        m_ServerThread.setName("UdpProxyServerThread");
        m_ServerThread.start();
    }

    public void stop() {
        this.Stopped = true;
        if (m_Selector != null) {
            try {
                m_Selector.close();
                m_Selector = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (udpServerChannel != null) {
            try {
                udpServerChannel.close();
                udpServerChannel = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                m_Selector.select();
                Iterator<SelectionKey> keyIterator = m_Selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        try {
                            if (key.isReadable()) {
                                //check custom channel exist
                                ((Tunnel) key.attachment()).onReadable(key);
                            } else if (key.isWritable()) {
                                ((Tunnel) key.attachment()).onWritable(key);
                            }
                        } catch (Exception e) {
                            Log.d(TAG, e.toString());
                        }
                    }
                    keyIterator.remove();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            this.stop();
            Log.d(TAG, "TcpServer thread exited.");
        }
    }

//    private void onAccepted(SelectionKey key) {
//        Tunnel localTunnel = null;
//        try {
//            SocketChannel localChannel = udpServerChannel.accept();
//            localTunnel = TunnelFactory.wrap(localChannel, m_Selector);
//
//            InetSocketAddress destAddress = getDestAddress(localChannel);
//            if (destAddress != null) {
//                Tunnel remoteTunnel = TunnelFactory.createTunnelByConfig(destAddress, m_Selector, TcpTunnel.class);
//                remoteTunnel.setBrotherTunnel(localTunnel);//关联兄弟
//                localTunnel.setBrotherTunnel(remoteTunnel);//关联兄弟
//                remoteTunnel.connect(destAddress);//开始连接
//            } else {
//                Log.d(TAG, String.format("Error: socket(%s:%d) target host is null.", localChannel.socket().getInetAddress().toString(), localChannel.socket().getPort()));
//                localTunnel.dispose();
//            }
//        } catch (Exception e) {
//            Log.d(TAG, "Error: remote socket create failed: " + e.toString());
//            if (localTunnel != null) {
//                localTunnel.dispose();
//            }
//        }
//    }

//    private InetSocketAddress getDestAddress(SocketChannel localChannel) {
//        short portKey = (short) localChannel.socket().getPort();
//        NatSession session = NatSessionManager.getSession(portKey);
//        if (session != null) {
//            if (ProxyConfig.Instance.needProxy(session.RemoteHost, session.RemoteIP)) {
//                Log.d(TAG, String.format("%d/%d:[PROXY] %s=>%s:%d\n", NatSessionManager.getSessionCount(), Tunnel.SessionCount, session.RemoteHost, CommonMethods.ipIntToString(session.RemoteIP), session.RemotePort & 0xFFFF));
//                InetSocketAddress desAddr;
//                if (session.RemoteHost != null) {
//                    desAddr = new InetSocketAddress(session.RemoteHost, session.RemotePort);
//                } else {
//                    desAddr = new InetSocketAddress(CommonMethods.ipIntToInet4Address(session.RemoteIP), session.RemotePort);
//                }
//                return desAddr;
//            } else {
//                return new InetSocketAddress(localChannel.socket().getInetAddress(), session.RemotePort & 0xFFFF);
//            }
//        }
//        return null;
//    }



}
