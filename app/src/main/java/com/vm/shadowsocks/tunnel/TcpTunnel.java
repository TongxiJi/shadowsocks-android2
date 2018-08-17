package com.vm.shadowsocks.tunnel;

import com.vm.shadowsocks.socks.AddrRequest;
import com.vm.shadowsocks.socks.SocksAddressType;
import com.vm.shadowsocks.tunnel.shadowsocks.CryptFactory;
import com.vm.shadowsocks.tunnel.shadowsocks.ICrypt;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;

public class TcpTunnel extends Tunnel {

    private static final String TAG = TcpTunnel.class.getSimpleName();
    private ICrypt m_Encryptor;
    private ShadowsocksConfig m_Config;
    private boolean m_TunnelEstablished;

    public TcpTunnel(ShadowsocksConfig config, Selector selector) throws Exception {
        super(config.ServerAddress, selector);
        m_Config = config;
        m_Encryptor = CryptFactory.get(m_Config.EncryptMethod, m_Config.Password);
    }

    @Override
    protected void onConnected(ByteBuffer buffer) throws Exception {
        buffer.clear();
        InetSocketAddress descAddr = getDestAddress();
        // https://shadowsocks.org/en/spec/protocol.html
        AddrRequest addrRequest;
        if (descAddr.getHostString() != null) {
            addrRequest = new AddrRequest(SocksAddressType.DOMAIN, descAddr.getHostString(), descAddr.getPort());
        } else {
            addrRequest = new AddrRequest(SocksAddressType.IPv4, descAddr.getAddress().getHostAddress(), descAddr.getPort());
        }
//        Log.d(TAG, addrRequest.toString());
        addrRequest.encodeAsByteBuf(buffer);
        buffer.flip();
        ByteBuffer addrBuff = ByteBuffer.allocate(buffer.remaining());
        addrBuff.put(buffer);

        buffer.clear();
        buffer.put(m_Encryptor.encrypt(addrBuff.array()));
        buffer.flip();

        if (write(buffer, true)) {
            m_TunnelEstablished = true;
            onTunnelEstablished();
        } else {
            m_TunnelEstablished = true;
            this.beginReceive();
        }
    }

    @Override
    protected boolean isTunnelEstablished() {
        return m_TunnelEstablished;
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {

        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);

        byte[] newbytes = m_Encryptor.encrypt(bytes);

        buffer.clear();
        buffer.put(newbytes);
        buffer.flip();
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        byte[] newbytes = m_Encryptor.decrypt(bytes);
        String s = new String(newbytes);
        buffer.clear();
        buffer.put(newbytes);
        buffer.flip();
    }

    @Override
    protected void onDispose() {
        m_Config = null;
        m_Encryptor = null;
    }

}
