package com.vm.shadowsocks.tunnel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;

/**
 * TODO not completed
 */
public class UdpRawTunnel extends UdpBaseTunnel {

    public UdpRawTunnel(InetSocketAddress serverAddress, Selector selector) throws Exception {
        super(serverAddress, selector);
    }

    public UdpRawTunnel(DatagramChannel innerChannel, Selector selector) {
        super(innerChannel, selector);
        // TODO Auto-generated constructor stub
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    protected boolean isTunnelEstablished() {
        return true;
    }

    @Override
    protected void onDispose() {
        // TODO Auto-generated method stub

    }

}
