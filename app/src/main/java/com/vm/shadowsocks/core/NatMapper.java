package com.vm.shadowsocks.core;


import android.util.Log;
import android.util.LruCache;

import com.vm.shadowsocks.tunnel.UdpBaseTunnel;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Author:tonnyji
 * E-MAIL:694270875@qq.com
 * Function:
 * Create Date:八月21,2018
 */
public class NatMapper {
    private static final String TAG = NatMapper.class.getSimpleName();
    private static LruCache<Integer, UdpBaseTunnel> udpTable = new LruCache<>(200);
    private static LruCache<Integer, UdpBaseTunnel> remoteUdpTable = new LruCache<>(200);

    static void putUdpChannel(Integer keyPort, UdpBaseTunnel udpChannel) {
//        Log.d(TAG, "start putUdpChannel");
        UdpBaseTunnel brotherTunnel = udpChannel.getBrotherTunnel();
        if (brotherTunnel == null) return;
        udpTable.put(keyPort, udpChannel);
        int remoteKeyPort = brotherTunnel.getInnerChannel().socket().getLocalPort();
        remoteUdpTable.put(remoteKeyPort, brotherTunnel);
//        Log.d(TAG, "putUdpChannel: remoteKeyPort:"+remoteKeyPort);
    }

    static Boolean containUdpChannel(Integer keyPort) {
        return udpTable.get(keyPort) != null;
    }

    static UdpBaseTunnel getUdpChannel(Integer keyPort) {
        return udpTable.get(keyPort);
    }

    static UdpBaseTunnel getRemoteUdpChannel(Integer keyPort) {
        return remoteUdpTable.get(keyPort);
    }

    private static UdpBaseTunnel removeUdpMapping(Integer keyPort) {
        UdpBaseTunnel tunnel = udpTable.remove(keyPort);
        if (tunnel == null) return null;
        UdpBaseTunnel brotherTunnel = tunnel.getBrotherTunnel();
        remoteUdpTable.remove(brotherTunnel.getInnerChannel().socket().getLocalPort());
        return tunnel;
    }

    static void closeChannelGracefully(Integer keyPort) throws IOException {
        UdpBaseTunnel udpChannel = removeUdpMapping(keyPort);
        if (udpChannel != null) {
            Log.d(TAG, "Proxy << Target Disconnect");
            udpChannel.getInnerChannel().close();
            UdpBaseTunnel broChannel = udpChannel.getBrotherTunnel();
            if (broChannel != null) {
                broChannel.getInnerChannel().close();
            }
        }
    }

    public static void remoteMapToString() {
        Set<Map.Entry<Integer, UdpBaseTunnel>> entrySet = remoteUdpTable.snapshot().entrySet();
        Log.d(TAG, "remoteMap size: " + entrySet.size());
        for (Map.Entry<Integer, UdpBaseTunnel> tunnelEntry : entrySet) {
            Log.d(TAG, "remoteMaptoString: " + tunnelEntry.getKey() + "  local port:" + tunnelEntry.getValue().getInnerChannel().socket().getLocalPort());
        }
    }
}
