package com.vm.shadowsocks.core;

import android.util.Log;
import android.util.LruCache;

import com.vm.shadowsocks.tcpip.CommonMethods;

import java.io.IOException;
import java.util.Map;

public class NatSessionManager {
    private static final String TAG = NatSessionManager.class.getSimpleName();

    private static final float MAX_SESSION_PERCENT = 0.6f;
    private static final long SESSION_TIMEOUT_NS = 60 * 1000000000L;

    private static final LruCache<Integer, NatSession> sessions = new LruCache<>(400);

    public static NatSession getSession(int portKey) {
        return sessions.get(portKey);
    }

    public static int getSessionCount() {
        return sessions.size();
    }

    static void clearExpiredSessions() {
        long now = System.nanoTime();
        Map<Integer, NatSession> snapshotMap = sessions.snapshot();
        for (Map.Entry<Integer, NatSession> entry : snapshotMap.entrySet()) {
            NatSession session = entry.getValue();
            if (now - session.LastNanoTime > SESSION_TIMEOUT_NS) {
                sessions.remove(entry.getKey());
                try {
                    NatMapper.closeChannelGracefully(entry.getKey());
                } catch (IOException e) {
                    Log.e(TAG, "clearExpiredSessions: " + e.getMessage());
                }
            }
        }
    }

    public static NatSession createSession(int portKey, int remoteIP, int remotePort) {
        Log.d(TAG, String.format("createSession portKey:%d remoteIP:%s remotePort:%d", portKey, CommonMethods.ipIntToString(remoteIP), remotePort));
        if (sessions.size() > MAX_SESSION_PERCENT * sessions.size()) {
            clearExpiredSessions();//清理过期的会话。
        }

        NatSession session = new NatSession();
        session.LastNanoTime = System.nanoTime();
        session.RemoteIP = remoteIP;
        session.RemotePort = remotePort;

        if (ProxyConfig.isFakeIP(remoteIP)) {
            session.RemoteHost = DnsProxyServer.reverseLookup(remoteIP);
        }

//        if (session.RemoteHost == null) {
//            session.RemoteHost = CommonMethods.ipIntToString(remoteIP);
//        }
        sessions.put(portKey, session);
        return session;
    }
}
