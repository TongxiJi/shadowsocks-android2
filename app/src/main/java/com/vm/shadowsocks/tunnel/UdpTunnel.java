package com.vm.shadowsocks.tunnel;

import com.vm.shadowsocks.tunnel.shadowsocks.CryptFactory;
import com.vm.shadowsocks.tunnel.shadowsocks.ICrypt;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.nio.ByteBuffer;
import java.nio.channels.Selector;

/**
 * Author:tonnyji
 * E-MAIL:694270875@qq.com
 * Function:
 * Create Date:八月20,2018
 */
public class UdpTunnel extends Tunnel {
    private static final String TAG = UdpTunnel.class.getSimpleName();
    private ICrypt m_Encryptor;
    private ShadowsocksConfig m_Config;
    private boolean m_TunnelEstablished;

    public UdpTunnel(ShadowsocksConfig config, Selector selector) throws Exception {
        super(config.ServerAddress, selector);
        m_Config = config;
        m_Encryptor = CryptFactory.get(m_Config.EncryptMethod, m_Config.Password);
    }

    @Override
    protected void onConnected(ByteBuffer buffer) throws Exception {

    }

    @Override
    protected boolean isTunnelEstablished() {
        return false;
    }

    @Override
    protected void beforeSend(ByteBuffer buffer) throws Exception {

    }

    @Override
    protected void afterReceived(ByteBuffer buffer) throws Exception {

    }

    @Override
    protected void onDispose() {

    }
}
