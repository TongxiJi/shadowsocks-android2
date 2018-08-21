package com.vm.shadowsocks.core;


import android.util.Log;
import android.util.LruCache;

import java.io.IOException;
import java.nio.channels.DatagramChannel;

/**
 * Author:tonnyji
 * E-MAIL:694270875@qq.com
 * Function:
 * Create Date:八月21,2018
 */
public class NatMapper {
    private static final String TAG = NatMapper.class.getSimpleName();
    private static LruCache<Integer, DatagramChannel> udpTable = new LruCache<>(200);

    static void putUdpChannel(Integer keyPort, DatagramChannel udpChannel) {
        udpTable.put(keyPort, udpChannel);
    }

    static Boolean containUdpChannel(Integer keyPort) {
        return udpTable.get(keyPort) != null;
    }

    static DatagramChannel getUdpChannel(Integer keyPort) {
        return udpTable.get(keyPort);
    }

    private static DatagramChannel removeUdpMapping(Integer keyPort) {
        return udpTable.remove(keyPort);
    }

    static void closeChannelGracefully(Integer keyPort) throws IOException {
        DatagramChannel udpChannel = removeUdpMapping(keyPort);
        if (udpChannel != null) {
            Log.d(TAG, "Proxy << Target Disconnect");
            udpChannel.close();
        }
    }
}
