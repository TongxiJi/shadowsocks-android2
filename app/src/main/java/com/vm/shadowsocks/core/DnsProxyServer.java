package com.vm.shadowsocks.core;

import android.util.Log;
import android.util.LruCache;

import com.vm.shadowsocks.dns.DnsPacket;
import com.vm.shadowsocks.dns.Question;
import com.vm.shadowsocks.dns.Resource;
import com.vm.shadowsocks.dns.ResourcePointer;
import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tcpip.IPHeader;
import com.vm.shadowsocks.tcpip.UDPHeader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;


public class DnsProxyServer implements Runnable {

    private static final String TAG = DnsProxyServer.class.getSimpleName();

    private class QueryState {
        public short ClientQueryID;
        public long QueryNanoTime;
        public int ClientIP;
        public int ClientPort;
        public int RemoteIP;
        public int RemotePort;
    }

    public boolean Stopped;
    private final int Port;
    private static final LruCache<Integer, String> IPDomainMaps = new LruCache<Integer, String>(150);
    private static final LruCache<String, Integer> DomainIPMaps = new LruCache<String, Integer>(150);
    private final long QUERY_TIMEOUT_NS = 10 * 1000000000L;
    private DatagramChannel m_Client;
    private Thread m_ReceivedThread;
    private short m_QueryID;
    private LruCache<Short, QueryState> m_QueryArray = new LruCache<>(100);

    public DnsProxyServer() throws IOException {
        m_Client = DatagramChannel.open();
        m_Client.configureBlocking(true);
        m_Client.socket().bind(new InetSocketAddress(0));
        this.Port = m_Client.socket().getLocalPort();
        Log.d(TAG, String.format("DnsServer listen on %d success.\n", this.Port & 0xFFFF));
    }

    public static String reverseLookup(int ip) {
        return IPDomainMaps.get(ip);
    }

    public void start() {
        m_ReceivedThread = new Thread(this);
        m_ReceivedThread.setName("DnsProxyThread");
        m_ReceivedThread.start();
    }

    public void stop() throws IOException {
        Stopped = true;
        if (m_Client != null) {
            m_Client.close();
            m_Client = null;
        }
    }

    @Override
    public void run() {
        try {
            ByteBuffer dnsBuffer = ByteBuffer.allocate(2000);
            IPHeader ipHeader = new IPHeader(dnsBuffer.array(), 0);
            UDPHeader udpHeader = new UDPHeader(dnsBuffer.array(), 20);

            dnsBuffer.position(28);
            dnsBuffer = dnsBuffer.slice();
            while (m_Client != null && m_Client.isOpen()) {
                dnsBuffer.clear();
                SocketAddress socketAddress = m_Client.receive(dnsBuffer);
                Log.d(TAG, "dns response received from "+socketAddress.toString() + " position:" +dnsBuffer.position());
                dnsBuffer.flip();
                try {
                    DnsPacket dnsPacket = DnsPacket.FromBytes(dnsBuffer);
                    if (dnsPacket != null) {
                        OnDnsResponseReceived(ipHeader, udpHeader, dnsPacket);
                    }
                } catch (Exception e) {
                    Log.d(TAG, String.format("Parse dns error: %s", e.getMessage()));
                }
            }
        } catch (Exception e) {
            Log.d(TAG, e.getMessage());
        } finally {
            Log.d(TAG, "DnsResolver Thread Exited.");
            try {
                this.stop();
            } catch (IOException ignore) {
            }
        }
    }

    private int getFirstIP(DnsPacket dnsPacket) {
        for (int i = 0; i < dnsPacket.Header.ResourceCount; i++) {
            Resource resource = dnsPacket.Resources[i];
            if (resource.Type == 1) {
                int ip = CommonMethods.readInt(resource.Data, 0);
                return ip;
            }
        }
        return 0;
    }

    private void tamperDnsResponse(byte[] rawPacket, DnsPacket dnsPacket, int newIP) {
        Question question = dnsPacket.Questions[0];

        dnsPacket.Header.setResourceCount((short) 1);
        dnsPacket.Header.setAResourceCount((short) 0);
        dnsPacket.Header.setEResourceCount((short) 0);

        ResourcePointer rPointer = new ResourcePointer(rawPacket, question.Offset() + question.Length());
        rPointer.setDomain((short) 0xC00C);
        rPointer.setType(question.Type);
        rPointer.setClass(question.Class);
        rPointer.setTTL(ProxyConfig.Instance.getDnsTTL());
        rPointer.setDataLength((short) 4);
        rPointer.setIP(newIP);

        dnsPacket.Size = 12 + question.Length() + 16;
    }

    private int getOrCreateFakeIP(String domainString) {
        Integer fakeIP = DomainIPMaps.get(domainString);
        if (fakeIP == null) {
            int hashIP = domainString.hashCode();
            do {
                fakeIP = ProxyConfig.FAKE_NETWORK_IP | (hashIP & 0x0000FFFF);
                hashIP++;
            } while (IPDomainMaps.get(fakeIP) != null);
            DomainIPMaps.put(domainString, fakeIP);
            IPDomainMaps.put(fakeIP, domainString);
        }
        return fakeIP;
    }

    private boolean dnsPollution(byte[] rawPacket, DnsPacket dnsPacket) {
        if (dnsPacket.Header.QuestionCount > 0) {
            Question question = dnsPacket.Questions[0];
            if (question.Type == 1) {
                int realIP = getFirstIP(dnsPacket);
                if (ProxyConfig.Instance.needProxy(question.Domain, realIP)) {
                    int fakeIP = getOrCreateFakeIP(question.Domain);
                    tamperDnsResponse(rawPacket, dnsPacket, fakeIP);
                    Log.d(TAG, String.format("FakeDns: %s=>%s(%s)\n", question.Domain, CommonMethods.ipIntToString(realIP), CommonMethods.ipIntToString(fakeIP)));
                    return true;
                }
            }
        }
        return false;
    }

    private void OnDnsResponseReceived(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        QueryState state = m_QueryArray.get(dnsPacket.Header.ID);
        if (state != null) {
            m_QueryArray.remove(dnsPacket.Header.ID);
        }

        if (state != null) {
            //DNS污染，默认污染海外网站
            dnsPollution(udpHeader.m_Data, dnsPacket);

            dnsPacket.Header.setID(state.ClientQueryID);
            ipHeader.setSourceIP(state.RemoteIP);
            ipHeader.setDestinationIP(state.ClientIP);
            ipHeader.setProtocol(IPHeader.UDP);
            ipHeader.setTotalLength(20 + 8 + dnsPacket.Size);
            udpHeader.setSourcePort(state.RemotePort);
            udpHeader.setDestinationPort(state.ClientPort);
            udpHeader.setTotalLength(8 + dnsPacket.Size);

            LocalVpnService.Instance.sendUDPPacket(ipHeader, udpHeader);
        }
    }

    private int getIPFromCache(String domain) {
        Integer ip = DomainIPMaps.get(domain);
        if (ip == null) {
            return 0;
        } else {
            return ip;
        }
    }

    private boolean interceptDns(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        Question question = dnsPacket.Questions[0];
        Log.d(TAG, "DNS Qeury " + question.Domain);
        if (question.Type == 1) {
            if (ProxyConfig.Instance.needProxy(question.Domain, getIPFromCache(question.Domain))) {
                int fakeIP = getOrCreateFakeIP(question.Domain);
                tamperDnsResponse(ipHeader.m_Data, dnsPacket, fakeIP);

                Log.d(TAG, String.format("interceptDns FakeDns: %s=>%s\n", question.Domain, CommonMethods.ipIntToString(fakeIP)));

                int sourceIP = ipHeader.getSourceIP();
                int sourcePort = udpHeader.getSourcePort();
                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                ipHeader.setDestinationIP(sourceIP);
                ipHeader.setTotalLength(20 + 8 + dnsPacket.Size);
                udpHeader.setSourcePort(udpHeader.getDestinationPort());
                udpHeader.setDestinationPort(sourcePort);
                udpHeader.setTotalLength(8 + dnsPacket.Size);
                LocalVpnService.Instance.sendUDPPacket(ipHeader, udpHeader);
                return true;
            }
        }
        return false;
    }

    private void clearExpiredQueries() {
        long now = System.nanoTime();
        Map<Short, QueryState> snapshotMap = m_QueryArray.snapshot();
        for (Map.Entry<Short, QueryState> entry : snapshotMap.entrySet()) {
            QueryState state = entry.getValue();
            if (now - state.QueryNanoTime > QUERY_TIMEOUT_NS) {
                m_QueryArray.remove(entry.getKey());
            }
        }
    }

    public void onDnsRequestReceived(IPHeader ipHeader, UDPHeader udpHeader, DnsPacket dnsPacket) {
        if (!interceptDns(ipHeader, udpHeader, dnsPacket)) {
            //转发DNS
            QueryState state = new QueryState();
            state.ClientQueryID = dnsPacket.Header.ID;
            state.QueryNanoTime = System.nanoTime();
            state.ClientIP = ipHeader.getSourceIP();
            state.ClientPort = udpHeader.getSourcePort();
            state.RemoteIP = ipHeader.getDestinationIP();
            state.RemotePort = udpHeader.getDestinationPort();

            // 转换QueryID
            m_QueryID++;// 增加ID
            dnsPacket.Header.setID(m_QueryID);

            clearExpiredQueries();//清空过期的查询，减少内存开销。
            m_QueryArray.put(m_QueryID, state);// 关联数据

            InetSocketAddress remoteAddress = new InetSocketAddress(CommonMethods.ipIntToInet4Address(state.RemoteIP), state.RemotePort);
            ByteBuffer packet = ByteBuffer.allocate(dnsPacket.Size);
            packet.put(udpHeader.m_Data, udpHeader.m_Offset + 8, dnsPacket.Size);
            try {
                if (LocalVpnService.Instance.protect(m_Client.socket())) {
                    packet.flip();
                    m_Client.send(packet, remoteAddress);
                    Log.d(TAG, "udp send  to :" + remoteAddress.toString());
                } else {
                    Log.d(TAG, "VPN protect udp socket failed.");
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
