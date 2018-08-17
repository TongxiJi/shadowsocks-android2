package com.vm.shadowsocks.core;

import com.vm.shadowsocks.tunnel.Config;
import com.vm.shadowsocks.tunnel.RawTunnel;
import com.vm.shadowsocks.tunnel.TcpTunnel;
import com.vm.shadowsocks.tunnel.Tunnel;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class TunnelFactory {

    private static final String TAG = TunnelFactory.class.getSimpleName();

    public static Tunnel wrap(SocketChannel channel, Selector selector) {
        return new RawTunnel(channel, selector);
    }

    public static Tunnel createTunnelByConfig(InetSocketAddress destAddress, Selector selector, Class<? extends Tunnel> tunnel) throws Exception {
        if (tunnel == TcpTunnel.class) {
            Config config = ProxyConfig.Instance.getDefaultTunnelConfig(destAddress);
            if (config instanceof ShadowsocksConfig) {
//                return new TcpTunnel((ShadowsocksConfig) config, selector);
                return tunnel
                        .getDeclaredConstructor(ShadowsocksConfig.class, Selector.class)
                        .newInstance((ShadowsocksConfig) config, selector);
            }
            throw new Exception("The config is unknow.");
        } else {
            return new RawTunnel(destAddress, selector);
        }
    }

}
