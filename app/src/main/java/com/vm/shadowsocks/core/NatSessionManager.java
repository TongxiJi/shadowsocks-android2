package com.vm.shadowsocks.core;

import android.util.LruCache;

import java.util.Map;

public class NatSessionManager {
    private static final int MAX_SESSION_COUNT = 60;
    private static final long SESSION_TIMEOUT_NS = 60 * 1000000000L;

    private static final LruCache<Integer, NatSession> sessions = new LruCache<>(100);

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
            }
        }
    }

    public static NatSession createSession(int portKey, int remoteIP, int remotePort) {
        if (sessions.size() > MAX_SESSION_COUNT) {
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
