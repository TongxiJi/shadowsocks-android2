package com.vm.shadowsocks.tunnel;

import android.util.Log;

import com.vm.shadowsocks.core.LocalVpnService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * TODO not completed
 */
public abstract class UdpBaseTunnel {
    private static final String TAG = UdpBaseTunnel.class.getSimpleName();
    public static final int UDP_BUFFER_SIZE = 64 * 1024;

//    protected ByteBuffer buffer = ByteBuffer.allocate(UDP_BUFFER_SIZE);

    public static long SessionCount;

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    private DatagramChannel m_InnerChannel;
    private Selector m_Selector;
    private UdpBaseTunnel m_BrotherTunnel;
    private boolean m_Disposed;
    private InetSocketAddress m_ServerEP;
    private InetSocketAddress m_DestAddress;

    public UdpBaseTunnel(DatagramChannel innerChannel, Selector selector) {
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        SessionCount++;
    }

    public UdpBaseTunnel(InetSocketAddress serverAddress, Selector selector) throws IOException {
        DatagramChannel innerChannel = DatagramChannel.open();
        innerChannel.configureBlocking(false);
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        this.m_ServerEP = serverAddress;
        SessionCount++;
    }

    public DatagramChannel getInnerChannel() {
        return m_InnerChannel;
    }

    public UdpBaseTunnel getBrotherTunnel() {
        return m_BrotherTunnel;
    }

    public InetSocketAddress getDestAddress() {
        return m_DestAddress;
    }

    public void setDestAddress(InetSocketAddress m_DestAddress) {
        this.m_DestAddress = m_DestAddress;
    }

    public void setBrotherTunnel(UdpBaseTunnel brotherTunnel) {
        m_BrotherTunnel = brotherTunnel;
    }


    public void connect(InetSocketAddress destAddress) throws Exception {
        if (LocalVpnService.Instance.protect(m_InnerChannel.socket())) {//保护socket不走vpn
            m_DestAddress = destAddress;
            m_InnerChannel.configureBlocking(false);
//            channel.socket().connect(m_ServerEP);
            m_InnerChannel.socket().bind(null);
//            beginReceive();
            m_InnerChannel.register(m_Selector, SelectionKey.OP_READ);//注册读事件
            Log.d(TAG, "connect: " + m_ServerEP.toString());
        } else {
            throw new Exception("VPN protect socket failed.");
        }
    }

    public InetSocketAddress getServerEP() {
        return m_ServerEP;
    }

    protected void beginReceive() throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        m_InnerChannel.register(m_Selector, SelectionKey.OP_READ, this);//注册读事件
    }


    protected void write(ByteBuffer buffer) throws Exception {
        int bytesSent = m_InnerChannel.send(buffer, m_ServerEP);
        Log.d(TAG, String.format("channel(%d)  send %d %d<->%s<->%s", this.hashCode(), m_InnerChannel.socket().getLocalPort(), bytesSent, m_ServerEP.toString(), getDestAddress()));
    }

    public void onReceived(SelectionKey key, ByteBuffer buffer, InetSocketAddress remoteAddr) throws Exception {
        this.m_ServerEP = remoteAddr;

        int bytesRead = buffer.limit();
//        Log.d(TAG, "onReceived: " + bytesRead);
        if (bytesRead > 0) {
            afterReceived(buffer);//先让子类处理，例如解密数据。
            if (buffer.hasRemaining()) {//将读到的数据，转发给兄弟。
                m_BrotherTunnel.beforeSend(buffer);//发送之前，先让子类处理，例如做加密等。
                m_BrotherTunnel.write(buffer);
            } else {
                Log.d(TAG, "not sent to brother tunnel" + buffer.hasRemaining());
            }
        }
    }

    public void onWritable(SelectionKey key) throws Exception {
        key.cancel();//取消写事件。
        m_BrotherTunnel.beginReceive();//这边数据发送完毕，通知兄弟可以收数据了。
    }

//    public void dispose() {
//        disposeInternal(true);
//    }

    public void disposeInternal(boolean disposeBrother) {
        if (m_Disposed) {
            return;
        } else {
            try {
                m_InnerChannel.close();
            } catch (Exception e) {
            }

            if (m_BrotherTunnel != null && disposeBrother) {
                m_BrotherTunnel.disposeInternal(false);//把兄弟的资源也释放了。
            }

            m_InnerChannel = null;
            m_Selector = null;
            m_BrotherTunnel = null;
            m_Disposed = true;
            SessionCount--;

            onDispose();
        }
    }
}
