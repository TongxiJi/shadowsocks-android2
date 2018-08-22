package com.vm.shadowsocks.tunnel;

import android.util.Log;

import com.vm.shadowsocks.socks.AddrRequest;
import com.vm.shadowsocks.socks.SocksAddressType;
import com.vm.shadowsocks.tunnel.shadowsocks.CryptFactory;
import com.vm.shadowsocks.tunnel.shadowsocks.ICrypt;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;

/**
 * Author:tonnyji
 * E-MAIL:694270875@qq.com
 * Function:
 * Create Date:八月20,2018
 * TODO not completed
 */
public class UdpTunnel extends UdpBaseTunnel {
    private static final String TAG = UdpTunnel.class.getSimpleName();
    private ICrypt m_Encryptor;
    private ShadowsocksConfig m_Config;
    private boolean m_TunnelEstablished;

    public UdpTunnel(ShadowsocksConfig config, Selector selector) throws Exception {
        super(config.ServerAddress, selector);
        m_Config = config;
        m_Encryptor = CryptFactory.get(m_Config.EncryptMethod, m_Config.Password);
        this.m_TunnelEstablished = true;
    }

    @Override
    protected boolean isTunnelEstablished() {
        return m_TunnelEstablished;
    }

    @Override
    protected void beforeSend(ByteBuffer rawBuff) throws Exception {
        ByteBuffer addrBuff = ByteBuffer.allocate(256);
        InetSocketAddress descAddr = getDestAddress();
        // https://shadowsocks.org/en/spec/protocol.html
        AddrRequest addrRequest;
        if (descAddr.getHostString() != null) {
            addrRequest = new AddrRequest(SocksAddressType.DOMAIN, descAddr.getHostString(), descAddr.getPort());
        } else {
            addrRequest = new AddrRequest(SocksAddressType.IPv4, descAddr.getAddress().getHostAddress(), descAddr.getPort());
        }
//        Log.d(TAG, addrRequest.toString());
        addrRequest.encodeAsByteBuf(addrBuff);//address frame

        ByteBuffer finalBuff = ByteBuffer.allocate(UDP_BUFFER_SIZE)
                .put(addrBuff)
                .put(rawBuff);
        finalBuff.flip();

        byte[] bytes = new byte[finalBuff.limit()];
        finalBuff.get(bytes);
        bytes = m_Encryptor.encrypt(bytes);

        rawBuff.clear();
        rawBuff.put(bytes);
        rawBuff.flip();
    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {
        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        bytes = m_Encryptor.decrypt(bytes);

        ByteBuffer decBuff = ByteBuffer.allocate(UDP_BUFFER_SIZE);
        decBuff.put(bytes);
        decBuff.flip();

        AddrRequest addrRequest = AddrRequest.getAddrRequest(decBuff);
        Log.e(TAG, "receive data from: " + addrRequest.toString());

        buffer.clear();
        buffer.put(decBuff);
    }

    @Override
    protected void onDispose() {
        m_Config = null;
        m_Encryptor = null;
    }
}
