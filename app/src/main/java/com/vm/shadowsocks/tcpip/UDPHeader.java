package com.vm.shadowsocks.tcpip;

public class UDPHeader {
    private static final short offset_src_port = 0; // Source port
    private static final short offset_dest_port = 2; // Destination port
    private static final short offset_tlen = 4; // Datagram length
    private static final short offset_crc = 6; // Checksum

    public byte[] m_Data;
    public int m_Offset;

    public UDPHeader(byte[] data, int offset) {
        this.m_Data = data;
        this.m_Offset = offset;
    }

    public int getSourcePort() {
        return CommonMethods.readShort(m_Data, m_Offset + offset_src_port) & 0xFFFF;
    }

    public void setSourcePort(int value) {
        CommonMethods.writeShort(m_Data, m_Offset + offset_src_port, (short) value);
    }

    public int getDestinationPort() {
        return CommonMethods.readShort(m_Data, m_Offset + offset_dest_port) & 0xFFFF;
    }

    public void setDestinationPort(int value) {
        CommonMethods.writeShort(m_Data, m_Offset + offset_dest_port, (short) value);
    }

    public int getTotalLength() {
        return CommonMethods.readShort(m_Data, m_Offset + offset_tlen) & 0xFFFF;
    }

    public void setTotalLength(int value) {
        CommonMethods.writeShort(m_Data, m_Offset + offset_tlen, (short) value);
    }

    public short getCrc() {
        return CommonMethods.readShort(m_Data, m_Offset + offset_crc);
    }

    public void setCrc(short value) {
        CommonMethods.writeShort(m_Data, m_Offset + offset_crc, value);
    }

    @Override
    public String toString() {
        // TODO Auto-generated method stub
        return String.format("%d->%d", getSourcePort() & 0xFFFF,
                getDestinationPort() & 0xFFFF);
    }
}
