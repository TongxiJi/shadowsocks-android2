package com.vm.shadowsocks.tunnel;

import android.util.Log;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;

/**
 * TODO not completed
 */
public class UdpRawTunnel extends UdpBaseTunnel {

    private static final String TAG = UdpRawTunnel.class.getSimpleName();

    public UdpRawTunnel(InetSocketAddress serverAddress, Selector selector) throws Exception {
        super(serverAddress, selector);
    }

    public UdpRawTunnel(DatagramChannel innerChannel, Selector selector) throws ClosedChannelException {
        super(innerChannel, selector);
        innerChannel.register(selector, SelectionKey.OP_READ,this);//注册读事件
        // TODO Auto-generated constructor stub
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {
        // TODO Auto-generated method stub
//        Log.d(TAG, "beforeSend: ");
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        // TODO Auto-generated method stub
//        Log.d(TAG, "afterReceived: ");
    }

    @Override
    protected void onDispose() {
        // TODO Auto-generated method stub
    }

    @Override
    public void onReceived(SelectionKey key, ByteBuffer buffer, InetSocketAddress remoteAddr) throws Exception {
//        Log.d(TAG, "onReceived: ");
        super.onReceived(key, buffer, remoteAddr);
    }
}
