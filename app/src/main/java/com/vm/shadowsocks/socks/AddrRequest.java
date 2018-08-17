package com.vm.shadowsocks.socks;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public final class AddrRequest {
    private final SocksAddressType addressType;
    private final String host;
    private final int port;

    public AddrRequest(SocksAddressType addressType, String host, int port) {
        if (addressType == null) {
            throw new NullPointerException("addressType");
        } else if (host == null) {
            throw new NullPointerException("host");
        } else {
            switch (addressType) {
                case IPv4:
                    break;
                case DOMAIN:
                    if (IDN.toASCII(host).length() > 255) {
                        throw new IllegalArgumentException(host + " IDN: " + IDN.toASCII(host) + " exceeds 255 char limit");
                    }
                    break;
                case IPv6:
                case UNKNOWN:
            }

            if (port > 0 && port < 65536) {
                this.addressType = addressType;
                this.host = IDN.toASCII(host);
                this.port = port;
            } else {
                throw new IllegalArgumentException(port + " is not in bounds 0 < x < 65536");
            }
        }
    }


    public SocksAddressType addressType() {
        return this.addressType;
    }

    public String host() {
        return IDN.toUnicode(this.host);
    }

    public int port() {
        return this.port;
    }

    public void encodeAsByteBuf(ByteBuffer byteBuf) throws UnknownHostException {
        byteBuf.put(this.addressType.byteValue());
        switch (this.addressType) {
            case IPv4:
                byteBuf.put(Inet4Address.getByName(this.host).getAddress());
                byteBuf.putShort((short) this.port);
                break;
            case DOMAIN:
                byteBuf.put((byte) this.host.length());
                byteBuf.put(this.host.getBytes(Charset.forName("US-ASCII")));
                byteBuf.putShort((short) this.port);
                break;
            case IPv6:
                byteBuf.put(Inet6Address.getByName(this.host).getAddress());
                byteBuf.putShort((short) this.port);
        }
    }

    public static AddrRequest getAddrRequest(ByteBuffer byteBuf) throws UnknownHostException {
        AddrRequest request = null;
        SocksAddressType addressType = SocksAddressType.valueOf(byteBuf.get());
        String host;
        int port;
        switch (addressType) {
            case IPv4: {
                ByteBuffer ipv4Buff = ByteBuffer.allocate(4);
                ipv4Buff.put(byteBuf);
                host = Inet4Address.getByAddress(ipv4Buff.array()).toString();
                port = byteBuf.getShort();
                request = new AddrRequest(addressType, host, port);
                break;
            }
            case DOMAIN: {
                int fieldLength = byteBuf.get();
                ByteBuffer hostnameBuff = ByteBuffer.allocate(fieldLength);
                hostnameBuff.put(byteBuf);
                host = new String(hostnameBuff.array());
                port = byteBuf.getShort();
                request = new AddrRequest(addressType, host, port);
                break;
            }
            case IPv6: {
                ByteBuffer ipv6Buff = ByteBuffer.allocate(16);
                ipv6Buff.put(byteBuf);
                host = Inet6Address.getByAddress(ipv6Buff.array()).toString();
                port = byteBuf.getShort();
                request = new AddrRequest(addressType, host, port);
                break;
            }
            case UNKNOWN:
                break;
        }
        return request;
    }

    @Override
    public String toString() {
        return "AddrRequest{" +
                "addressType=" + addressType +
                ", host='" + host + '\'' +
                ", port=" + port +
                '}';
    }
}
