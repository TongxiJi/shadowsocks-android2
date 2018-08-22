package com.vm.shadowsocks.tunnel;

import android.util.Log;

import com.vm.shadowsocks.core.LocalVpnService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;

/**
 * TODO not completed
 */
public abstract class UdpBaseTunnel {
    private static final String TAG = UdpBaseTunnel.class.getSimpleName();

    protected ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);

    public static long SessionCount;

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    private AbstractSelectableChannel m_InnerChannel;
    private ByteBuffer m_SendRemainBuffer;
    private Selector m_Selector;
    private UdpBaseTunnel m_BrotherTunnel;
    private boolean m_Disposed;
    private InetSocketAddress m_ServerEP;
    private InetSocketAddress m_DestAddress;

    public UdpBaseTunnel(AbstractSelectableChannel innerChannel, Selector selector) {
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        SessionCount++;
    }

    public UdpBaseTunnel(InetSocketAddress serverAddress, Selector selector) throws IOException {
        SocketChannel innerChannel = SocketChannel.open();
        innerChannel.configureBlocking(false);
        this.m_InnerChannel = innerChannel;
        this.m_Selector = selector;
        this.m_ServerEP = serverAddress;
        SessionCount++;
    }

    public InetSocketAddress getDestAddress() {
        return m_DestAddress;
    }

    public void setBrotherTunnel(UdpBaseTunnel brotherTunnel) {
        m_BrotherTunnel = brotherTunnel;
    }


    public void listen(InetSocketAddress destAddress) throws Exception {
        DatagramChannel channel = (DatagramChannel) m_InnerChannel;
        if (LocalVpnService.Instance.protect(channel.socket())) {//保护socket不走vpn
            m_DestAddress = destAddress;
            channel.socket().bind(null);
            beginReceive();
        } else {
            throw new Exception("VPN protect socket failed.");
        }
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
            if (m_InnerChannel instanceof SocketChannel) {
                bytesSent = ((SocketChannel) m_InnerChannel).write(buffer);
            } else if (m_InnerChannel instanceof DatagramChannel) {
                bytesSent = ((DatagramChannel) m_InnerChannel).write(buffer);
            } else {
                throw new Exception("unsupported channel type:" + m_InnerChannel.getClass().getName());
            }

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

    public void onReadable(SelectionKey key) {
        try {
            buffer.clear();
            int bytesRead;
            if (m_InnerChannel instanceof SocketChannel) {
                bytesRead = ((SocketChannel) m_InnerChannel).read(buffer);
            } else if (m_InnerChannel instanceof DatagramChannel) {
                bytesRead = ((DatagramChannel) m_InnerChannel).read(buffer);
            } else {
                throw new Exception("unsupported channel type:" + m_InnerChannel.getClass().getName());
            }
            if (bytesRead > 0) {
                buffer.flip();
                afterReceived(buffer);//先让子类处理，例如解密数据。
                if (isTunnelEstablished() && buffer.hasRemaining()) {//将读到的数据，转发给兄弟。
                    m_BrotherTunnel.beforeSend(buffer);//发送之前，先让子类处理，例如做加密等。
                    if (!m_BrotherTunnel.write(buffer, true)) {
                        key.cancel();//兄弟吃不消，就取消读取事件。
                        Log.d(TAG, String.format("%s can not read more.\n", m_ServerEP));
                    }
                }
            } else if (bytesRead < 0) {
                this.dispose();//连接已关闭，释放资源。
            }
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
            this.dispose();
        }
    }

    public void onWritable(SelectionKey key) {
        try {
            this.beforeSend(m_SendRemainBuffer);//发送之前，先让子类处理，例如做加密等。
            if (this.write(m_SendRemainBuffer, false)) {//如果剩余数据已经发送完毕
                key.cancel();//取消写事件。
                if (isTunnelEstablished()) {
                    m_BrotherTunnel.beginReceive();//这边数据发送完毕，通知兄弟可以收数据了。
                } else {
                    this.beginReceive();//开始接收代理服务器响应数据
                }
            }
        } catch (Exception e) {
            this.dispose();
        }
    }

    public void dispose() {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother) {
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
