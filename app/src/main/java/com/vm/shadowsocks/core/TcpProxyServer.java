package com.vm.shadowsocks.core;

import android.util.Log;

import com.vm.shadowsocks.tunnel.Config;
import com.vm.shadowsocks.tunnel.TcpBaseTunnel;
import com.vm.shadowsocks.tunnel.TcpTunnel;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

public class TcpProxyServer implements Runnable {

    private static final String TAG = TcpProxyServer.class.getSimpleName();
    public boolean Stopped;
    public int Port;

    Selector m_Selector;
    ServerSocketChannel m_ServerSocketChannel;
    Thread m_ServerThread;

    public TcpProxyServer(int port) throws IOException {
        m_Selector = Selector.open();
        m_ServerSocketChannel = ServerSocketChannel.open();
        m_ServerSocketChannel.configureBlocking(false);
        m_ServerSocketChannel.socket().bind(new InetSocketAddress(port));
        m_ServerSocketChannel.register(m_Selector, SelectionKey.OP_ACCEPT);
        this.Port = m_ServerSocketChannel.socket().getLocalPort();
        Log.d(TAG, String.format("AsyncTcpServer listen on %d success.\n", this.Port & 0xFFFF));
    }

    public void start() {
        m_ServerThread = new Thread(this);
        m_ServerThread.setName("TcpProxyServerThread");
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

        if (m_ServerSocketChannel != null) {
            try {
                m_ServerSocketChannel.close();
                m_ServerSocketChannel = null;
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
                                ((TcpBaseTunnel) key.attachment()).onReadable(key);
                            } else if (key.isWritable()) {
                                ((TcpBaseTunnel) key.attachment()).onWritable(key);
                            } else if (key.isConnectable()) {
                                ((TcpBaseTunnel) key.attachment()).onConnectible();
                            } else if (key.isAcceptable()) {
                                onAccepted(key);
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

    private void onAccepted(SelectionKey key) {
        TcpBaseTunnel localTunnel = null;
        try {
            SocketChannel localChannel = m_ServerSocketChannel.accept();
            localTunnel = TunnelFactory.wrap(localChannel, m_Selector);
            InetSocketAddress destAddress = TunnelFactory.getDestAddress(localChannel);
//            Log.d(TAG, "onAccepted: destAddress :" + (destAddress.toString()));
            if (destAddress != null) {
                TcpBaseTunnel remoteTunnel;
                Config config = ProxyConfig.Instance.getDefaultTunnelConfig(destAddress);
                if (config instanceof ShadowsocksConfig) {
                    remoteTunnel = new TcpTunnel((ShadowsocksConfig) config, m_Selector);
                } else {
                    throw new Exception("unsupported config type:" + config.getClass().getSimpleName());
                }
                remoteTunnel.setBrotherTunnel(localTunnel);//关联兄弟
                localTunnel.setBrotherTunnel(remoteTunnel);//关联兄弟
                remoteTunnel.connect(destAddress);//开始连接
            } else {
                Log.e(TAG, String.format("socket(%s:%d) target host is null.", localChannel.socket().getInetAddress().toString(), localChannel.socket().getPort()));
                localTunnel.dispose();
            }
        } catch (Exception e) {
            Log.e(TAG, "remote socket create failed: " + e.toString());
            if (localTunnel != null) {
                localTunnel.dispose();
            }
        }
    }

}
