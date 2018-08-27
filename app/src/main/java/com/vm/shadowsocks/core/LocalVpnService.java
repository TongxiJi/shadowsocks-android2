package com.vm.shadowsocks.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.vm.shadowsocks.R;
import com.vm.shadowsocks.SSApplication;
import com.vm.shadowsocks.core.ProxyConfig.IPAddress;
import com.vm.shadowsocks.dns.DnsPacket;
import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tcpip.IPHeader;
import com.vm.shadowsocks.tcpip.TCPHeader;
import com.vm.shadowsocks.tcpip.UDPHeader;
import com.vm.shadowsocks.ui.MainActivity;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVpnService extends VpnService implements Runnable {
    private static final String TAG = LocalVpnService.class.getSimpleName();

    private static final int UDP_PROXY_NONE = 0;
    private static final int UDP_PROXY_FULL = 1;

    private boolean ENABLE_FAKE_DNS = false;

    private int UDP_PROXY_TYPE = 1;

    public static LocalVpnService Instance;
    public static String ProxyUrl;
    public static boolean IsRunning = false;

    private static int ID;
    private static int LOCAL_IP;
    private static ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<onStatusChangedListener, Object>();


    private ConnectivityManager connectivity = (ConnectivityManager) SSApplication.getAppContext().getSystemService(Context.CONNECTIVITY_SERVICE);

    private NetworkRequest defaultNetworkRequest = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

    private Network underlyingNetwork;

    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private UdpProxyServer udpProxyServer;
    private DnsProxyServer m_DnsProxyServer;
    private FileOutputStream m_VPNOutputStream;

    private byte[] m_Packet;
    private IPHeader m_IPHeader;
    private TCPHeader m_TCPHeader;
    private UDPHeader m_UDPHeader;
    private ByteBuffer m_DNSBuffer;
    private Handler m_Handler;
    private long m_SentBytes;
    private long m_ReceivedBytes;

    public LocalVpnService() {
        ID++;
        m_Handler = new Handler();
        m_Packet = new byte[1500];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        Log.d(TAG, String.format("New VPNService(%d)\n", ID));
    }


    private ConnectivityManager.NetworkCallback defaultNetworkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            underlyingNetwork = network;
            Log.d(TAG, "onAvailable: network:" + underlyingNetwork.toString());
//            NetworkInterface
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            underlyingNetwork = network;
        }

        @Override
        public void onLost(Network network) {
            underlyingNetwork = null;
        }
    };


    public Network getUnderlyingNetwork() {
        return underlyingNetwork;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, String.format("VPNService(%s) created.", ID));
        // Start a new session by creating a new thread.
        m_VPNThread = new Thread(this, "VPNServiceThread");
        m_VPNThread.start();
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        IsRunning = true;
        return super.onStartCommand(intent, flags, startId);
    }

    public interface onStatusChangedListener {
        public void onStatusChanged(String status, Boolean isRunning);

        public void onLogReceived(String logString);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        if (m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.remove(listener);
        }
    }

    private void onStatusChanged(final String status, final boolean isRunning) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onStatusChanged(status, isRunning);
                }
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onLogReceived(logString);
                }
            }
        });
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences("SmartProxy", MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.apply();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            // getPackageName()是你当前类的包名，0代表是获取版本信息
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            String version = packInfo.versionName;
            return version;
        } catch (Exception e) {
            return "0.0";
        }
    }

    @Override
    public synchronized void run() {
        try {
            Log.d(TAG, String.format("VPNService(%s) work thread is runing...\n", ID));

            ProxyConfig.AppInstallID = getAppInstallID();//获取安装ID
            ProxyConfig.AppVersion = getVersionName();//获取版本号
            Log.d(TAG, String.format("AppInstallID: %s\n", ProxyConfig.AppInstallID));
            Log.d(TAG, String.format("Android version: %s", Build.VERSION.RELEASE));
            Log.d(TAG, String.format("App version: %s", ProxyConfig.AppVersion));

            ChinaIpMaskManager.loadFromFile(getResources().openRawResource(R.raw.ipmask));//加载中国的IP段，用于IP分流。
            waitUntilPreapred();//检查是否准备完毕。

            Log.d(TAG, "Load config from file ...");
            try {
                ProxyConfig.Instance.loadFromFile(getResources().openRawResource(R.raw.config));
                Log.d(TAG, "Load done");
            } catch (Exception e) {
                String errString = e.getMessage();
                if (errString == null || errString.isEmpty()) {
                    errString = e.toString();
                }
                Log.d(TAG, String.format("Load failed with error: %s", errString));
            }

            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            Log.d(TAG, "LocalTcpServer started.");

            switch (UDP_PROXY_TYPE) {
                case UDP_PROXY_FULL:
                    udpProxyServer = new UdpProxyServer(0);
                    udpProxyServer.start();
                    Log.d(TAG, "UdpProxyServer started.");
                    break;
                default:
                    break;
            }

            if (ENABLE_FAKE_DNS) {
                m_DnsProxyServer = new DnsProxyServer();
                m_DnsProxyServer.start();
                Log.d(TAG, "LocalDnsProxy started.");
            }

            while (true) {
                if (IsRunning) {
                    //加载配置文件

                    Log.d(TAG, "set shadowsocks");
                    try {
                        ProxyConfig.Instance.m_ProxyList.clear();
                        ProxyConfig.Instance.addProxyToList(ProxyUrl);
                        Log.d(TAG, String.format("Proxy is: %s", ProxyConfig.Instance.getDefaultProxy()));
                    } catch (Exception e) {
                        String errString = e.getMessage();
                        if (errString == null || errString.isEmpty()) {
                            errString = e.toString();
                        }
                        IsRunning = false;
                        onStatusChanged(errString, false);
                        continue;
                    }
                    String welcomeInfoString = ProxyConfig.Instance.getWelcomeInfo();
                    if (welcomeInfoString != null && !welcomeInfoString.isEmpty()) {
                        Log.d(TAG, String.format("%s", ProxyConfig.Instance.getWelcomeInfo()));
                    }
                    Log.d(TAG, ("Global mode is " + (ProxyConfig.Instance.globalMode ? "on" : "off")));

                    runVPN();
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Log.d(TAG, e.getMessage());
        } catch (Exception e) {
            Log.d(TAG, String.format("Fatal error: %s", e.toString()));
        } finally {
            Log.d(TAG, "App terminated.");
            try {
                dispose();
            } catch (IOException ignored) {
            }
        }
    }

    private void runVPN() throws Exception {
        connectivity.registerNetworkCallback(defaultNetworkRequest, defaultNetworkCallback);
        this.m_VPNInterface = establishVPN();
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor());
        int size = 0;
        while (size != -1 && IsRunning) {
            while ((size = in.read(m_Packet)) > 0 && IsRunning) {
                if (m_TcpProxyServer.Stopped) {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }
                switch (UDP_PROXY_TYPE) {
                    case UDP_PROXY_FULL:
                        if (udpProxyServer != null && udpProxyServer.Stopped) {
                            throw new Exception("UdpServer stopped.");
                        }
                        break;
                    default:
                        break;
                }
                onIPPacketReceived(m_IPHeader, size);
            }
            Thread.sleep(1);
        }
        in.close();
        connectivity.unregisterNetworkCallback(defaultNetworkCallback);
        disconnectVPN();
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                Log.d(TAG, String.format("onIPPacketReceived:tcp  %s:%d <-> %s:%d",
                        CommonMethods.ipIntToString(ipHeader.getSourceIP()), tcpHeader.getSourcePort(),
                        CommonMethods.ipIntToString(ipHeader.getDestinationIP()), tcpHeader.getDestinationPort()
                ));
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {// 收到本地TCP服务器数据
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            Log.d(TAG, String.format("NoSession: %s %s\n", ipHeader.toString(), tcpHeader.toString()));
                        }
                    } else {

                        // 添加端口映射
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(),ipHeader.getSourceIP());
                        }

                        session.LastTime = System.currentTimeMillis();
                        session.PacketSent++;//注意顺序

                        int tcpDataSize = ipHeader.getDataLength() - 20;//tdp头一共20字节
//                        if (session.PacketSent == 2 && tcpDataSize == 0) {
//                            return;//丢弃tcp握手的第二个ACK报文。因为客户端发数据的时候也会带上ACK，这样可以在服务器Accept之前分析出HOST信息。
//                        }
                        // 转发给本地TCP服务器
                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;//注意顺序
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                Log.d(TAG, String.format("onIPPacketReceived:udp  %s:%d <-> %s:%d",
                        CommonMethods.ipIntToString(ipHeader.getSourceIP()), udpHeader.getSourcePort(),
                        CommonMethods.ipIntToString(ipHeader.getDestinationIP()), udpHeader.getDestinationPort()
                ));
//                if (UDP_PROXY_TYPE == UDP_PROXY_FAKE_DNS) {
//                    // 转发DNS数据包：
//                    if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
//                        m_DNSBuffer.clear();
//                        m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
//                        DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
//                        if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
//                            m_DnsProxyServer.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
//                        }
//                    }
//                } else
                if (UDP_PROXY_TYPE == UDP_PROXY_FULL) {
                    if (ipHeader.getSourceIP() == LOCAL_IP) {//TODO need add real network ip || ipHeader.getSourceIP() == CommonMethods.ipStringToInt("192.168.0.6") ,
                        if (ENABLE_FAKE_DNS && udpHeader.getDestinationPort() == 53) {
                            m_DNSBuffer.clear();
                            m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                            DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                            if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                                m_DnsProxyServer.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                            }
                            return;
                        }
                        if (udpHeader.getSourcePort() == udpProxyServer.Port) {// 收到本地UDP服务器数据
                            NatSession session = NatSessionManager.getSession(udpHeader.getDestinationPort());
                            if (session != null) {
                                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                                udpHeader.setSourcePort(session.RemotePort);
                                ipHeader.setDestinationIP(session.LocalIP);

                                CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
                                m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                                m_ReceivedBytes += size;
                            } else {
                                Log.d(TAG, String.format("NoSession: %s %s\n", ipHeader.toString(), udpHeader.toString()));
                            }
                        } else {
                            // 添加端口映射
                            int portKey = udpHeader.getSourcePort();
                            NatSession session = NatSessionManager.getSession(portKey);
//                            if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != udpHeader.getDestinationPort()) {
//                                session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), udpHeader.getDestinationPort());
//                            }

                            if (session == null) {
                                session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), udpHeader.getDestinationPort(), ipHeader.getSourceIP());
                            } else {
                                //当应用 单个udp_client向多台服务器发送数据时,需要改变目标地址
                                session.RemoteIP = ipHeader.getDestinationIP();
                                session.RemotePort = udpHeader.getDestinationPort();
                            }

                            session.LastTime = System.currentTimeMillis();
                            session.PacketSent++;//注意顺序

                            int udpDataSize = ipHeader.getDataLength() - 8;//udp头一共8字节

                            // 转发给本地UDP服务器
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            ipHeader.setDestinationIP(LOCAL_IP);
                            udpHeader.setDestinationPort(udpProxyServer.Port);

                            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            session.BytesSent += udpDataSize;//注意顺序
                            m_SentBytes += size;
                        }
                    }
                } else if (UDP_PROXY_TYPE == UDP_PROXY_NONE) {
                }
                break;
            default:
                Log.d(TAG, String.format("other protocol PacketReceived:  %s", CommonMethods.ipIntToInet4Address(ipHeader.getDestinationIP())));
                break;
        }
    }

    private void waitUntilPreapred() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private ParcelFileDescriptor establishVPN() throws Exception {
        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());
        Log.d(TAG, String.format("setMtu: %d\n", ProxyConfig.Instance.getMTU()));

        IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        Log.d(TAG, String.format("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength));
        Log.d(TAG, String.format("dns size: %d", ProxyConfig.Instance.getDnsList().size()));
        for (ProxyConfig.IPAddress dns : ProxyConfig.Instance.getDnsList()) {
            builder.addDnsServer(dns.Address);
            Log.d(TAG, String.format("addDnsServer: %s", dns.Address));
        }

        if (ProxyConfig.Instance.getRouteList().size() > 0) {
            for (ProxyConfig.IPAddress routeAddress : ProxyConfig.Instance.getRouteList()) {
                builder.addRoute(routeAddress.Address, routeAddress.PrefixLength);
                Log.d(TAG, String.format("addRoute: %s/%d\n", routeAddress.Address, routeAddress.PrefixLength));
            }
            builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);
            Log.d(TAG, String.format("addRoute for FAKE_NETWORK: %s/%d\n", CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16));
        } else {
            builder.addRoute("0.0.0.0", 0);
            Log.d(TAG, "addDefaultRoute: 0.0.0.0/0");
        }


        Class<?> SystemProperties = Class.forName("android.os.SystemProperties");
        Method method = SystemProperties.getMethod("get", new Class[]{String.class});
        ArrayList<String> servers = new ArrayList<String>();
        for (String name : new String[]{"net.dns1", "net.dns2", "net.dns3", "net.dns4",}) {
            String value = (String) method.invoke(null, name);
            if (value != null && !"".equals(value) && !servers.contains(value)) {
                servers.add(value);
                if (value.replaceAll("\\d", "").length() == 3) {//防止IPv6地址导致问题
                    builder.addRoute(value, 32);
                } else {
                    builder.addRoute(value, 128);
                }
                Log.d(TAG, String.format("%s=%s", name, value));
            }
        }

        if (AppProxyManager.isLollipopOrAbove) {
            if (AppProxyManager.Instance.proxyAppInfo.size() == 0) {
                Log.d(TAG, "Proxy All Apps");
            }
            for (AppInfo app : AppProxyManager.Instance.proxyAppInfo) {
                builder.addAllowedApplication("com.vm.shadowsocks");//需要把自己加入代理，不然会无法进行网络连接
                try {
                    builder.addAllowedApplication(app.getPkgName());
                    Log.d(TAG, "Proxy App: " + app.getAppLabel());
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Proxy App Fail: " + app.getAppLabel());
                }
            }
        } else {
            Log.d(TAG, "No Pre-App proxy, due to low Android version.");
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setConfigureIntent(pendingIntent);
        builder.setSession(ProxyConfig.Instance.getSessionName());
        ParcelFileDescriptor pfdDescriptor = builder.establish();
        onStatusChanged(ProxyConfig.Instance.getSessionName() + getString(R.string.vpn_connected_status), true);
        return pfdDescriptor;
    }

    public void disconnectVPN() {
        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }
        onStatusChanged(ProxyConfig.Instance.getSessionName() + getString(R.string.vpn_disconnected_status), false);
        this.m_VPNOutputStream = null;
    }

    private synchronized void dispose() throws IOException {
        // 断开VPN
        disconnectVPN();

        // 停止TcpServer
        if (m_TcpProxyServer != null) {
            m_TcpProxyServer.stop();
            m_TcpProxyServer = null;
            Log.d(TAG, "LocalTcpServer stopped.");
        }

        if (udpProxyServer != null) {
            udpProxyServer.stop();
            udpProxyServer = null;
            Log.d(TAG, "UdpProxyServer stopped.");
        }

        // 停止DNS解析器
        if (m_DnsProxyServer != null) {
            m_DnsProxyServer.stop();
            m_DnsProxyServer = null;
            Log.d(TAG, "LocalDnsProxy stopped.");
        }

        stopSelf();
        IsRunning = false;
        System.exit(0);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, String.format("VPNService(%s) destoried.", ID));
        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
        }
    }

}
