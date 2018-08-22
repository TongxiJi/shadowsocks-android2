package com.vm.shadowsocks.core;

import android.util.Log;

import com.vm.shadowsocks.tunnel.Config;
import com.vm.shadowsocks.tunnel.UdpTunnel;
import com.vm.shadowsocks.tunnel.UdpBaseTunnel;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

/**
 * TODO not completed
 */
public class UdpProxyServer implements Runnable {
    private static final String TAG = UdpProxyServer.class.getSimpleName();
    private static final int UDP_RECV_BUFF_SIZE = 64 * 1024;

    public boolean Stopped = true;
    public int Port;

    Selector m_Selector;
    DatagramChannel udpServerChannel;
    Thread m_ServerThread;

    public UdpProxyServer(int port) throws IOException {
        m_Selector = Selector.open();
        udpServerChannel = DatagramChannel.open();
        udpServerChannel.configureBlocking(false);
        udpServerChannel.socket().bind(new InetSocketAddress(port));
        udpServerChannel.register(m_Selector, SelectionKey.OP_READ);
        this.Port = udpServerChannel.socket().getLocalPort();
        Stopped = false;
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
                                ByteBuffer recvBuf = ByteBuffer.allocate(UdpBaseTunnel.UDP_BUFFER_SIZE);
                                InetSocketAddress remoteAddr = (InetSocketAddress) ((DatagramChannel) key.channel()).receive(recvBuf);
                                onCheckRemoteTunnel(key, remoteAddr);
                                ((UdpBaseTunnel) key.attachment()).onReceived(key, recvBuf);
                            } else if (key.isWritable()) {
                                ((UdpBaseTunnel) key.attachment()).onWritable(key);
                            }
                        } catch (Exception e) {
                            Log.d(TAG, e.toString());
                        }
                    }
                    keyIterator.remove();
                }
            }
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        } finally {
            this.stop();
            Log.d(TAG, "TcpServer thread exited.");
        }
    }

    private void onCheckRemoteTunnel(SelectionKey key, InetSocketAddress remoteAddr) throws Exception {
        UdpBaseTunnel localTunnel = null;
        try {
            DatagramChannel localChannel = (DatagramChannel) key.channel();
            InetSocketAddress destAddress = TunnelFactory.getDestAddress(remoteAddr.getPort());
            if (destAddress != null) {
                if (!NatMapper.containUdpChannel(remoteAddr.getPort())) {
                    localTunnel = TunnelFactory.wrap(localChannel, m_Selector);
                    UdpBaseTunnel remoteTunnel;
                    Config config = ProxyConfig.Instance.getDefaultTunnelConfig(destAddress);
                    if (config instanceof ShadowsocksConfig) {
                        remoteTunnel = new UdpTunnel((ShadowsocksConfig) config, m_Selector);
                        NatMapper.putUdpChannel(remoteAddr.getPort(), remoteTunnel.getInnerChannel());
                    } else {
                        throw new Exception("unsupported config type:" + config.getClass().getSimpleName());
                    }
                    remoteTunnel.setBrotherTunnel(localTunnel);//关联兄弟
                    localTunnel.setBrotherTunnel(remoteTunnel);//关联兄弟
                    remoteTunnel.connect(destAddress);
                }
            } else {
                throw new Exception(String.format("Error: socket(%s:%d) target address is null.", localChannel.socket().getLocalAddress(), localChannel.socket().getPort()));
            }
        } catch (Exception e) {
            if (localTunnel != null) {
                localTunnel.dispose();
            }
            throw new Exception("Error: remote socket create failed: " + e.toString());
        }
    }


}
