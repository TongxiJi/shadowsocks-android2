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

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    private DatagramChannel m_InnerChannel;
    private ByteBuffer m_SendRemainBuffer;
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
        DatagramChannel channel = m_InnerChannel;
        if (LocalVpnService.Instance.protect(channel.socket())) {//保护socket不走vpn
            m_DestAddress = destAddress;
            channel.configureBlocking(false);
//            channel.socket().connect(m_ServerEP);
            channel.socket().bind(null);
            onTunnelEstablished();
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


    protected boolean write(ByteBuffer buffer, boolean copyRemainData) throws Exception {
        int bytesSent = 0;
        while (buffer.hasRemaining()) {
//            bytesSent = m_InnerChannel.write();
            bytesSent = m_InnerChannel.send(buffer, m_ServerEP);
            Log.d(TAG, String.format("channel(%d)  send  %d<->%s<->%s", this.hashCode(), m_InnerChannel.socket().getLocalPort(), m_ServerEP.toString(), getDestAddress()));
            if (bytesSent == 0) {
                break;//不能再发送了，终止循环
            }
        }

        if (buffer.hasRemaining()) {//数据没有发送完毕
            if (copyRemainData) {//拷贝剩余数据，然后侦听写入事件，待可写入时写入。
                //拷贝剩余数据
                if (m_SendRemainBuffer == null) {
                    m_SendRemainBuffer = ByteBuffer.allocate(buffer.capacity());
                }
                m_SendRemainBuffer.clear();
                m_SendRemainBuffer.put(buffer);
                m_SendRemainBuffer.flip();
                m_InnerChannel.register(m_Selector, SelectionKey.OP_WRITE, this);//注册写事件
            }
            return false;
        } else {//发送完毕了
            return true;
        }
    }

    protected void onTunnelEstablished() throws Exception {
        this.beginReceive();//开始接收数据
        m_BrotherTunnel.beginReceive();//兄弟也开始收数据吧
    }

    public void onReceived(SelectionKey key, ByteBuffer buffer, InetSocketAddress remoteAddr) throws Exception {
        //remoteAddr对于local tunnel,只有port是真实的,会在nat表中进行转换
        this.m_ServerEP = remoteAddr;

        int bytesRead = buffer.limit();
//        Log.d(TAG, "onReceived: " + bytesRead);
        if (bytesRead > 0) {
            afterReceived(buffer);//先让子类处理，例如解密数据。
            if (isTunnelEstablished() && buffer.hasRemaining()) {//将读到的数据，转发给兄弟。
                m_BrotherTunnel.beforeSend(buffer);//发送之前，先让子类处理，例如做加密等。
                if (!m_BrotherTunnel.write(buffer, true)) {
                    key.cancel();//兄弟吃不消，就取消读取事件。
                    Log.d(TAG, String.format("%s can not read more.\n", m_ServerEP));
                }
            } else {
                Log.d(TAG, "not sent to brother tunnel" + isTunnelEstablished() + buffer.hasRemaining());
            }
        }
//        else if (bytesRead < 0) {
//            this.dispose();//连接已关闭，释放资源。
//        }
    }

    public void onWritable(SelectionKey key) throws Exception {
        this.beforeSend(m_SendRemainBuffer);//发送之前，先让子类处理，例如做加密等。
        if (this.write(m_SendRemainBuffer, false)) {//如果剩余数据已经发送完毕
            key.cancel();//取消写事件。
            if (isTunnelEstablished()) {
                m_BrotherTunnel.beginReceive();//这边数据发送完毕，通知兄弟可以收数据了。
            } else {
                this.beginReceive();//开始接收代理服务器响应数据
            }
        }
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
            m_SendRemainBuffer = null;
            m_Selector = null;
            m_BrotherTunnel = null;
            m_Disposed = true;
            SessionCount--;

            onDispose();
        }
    }
}
