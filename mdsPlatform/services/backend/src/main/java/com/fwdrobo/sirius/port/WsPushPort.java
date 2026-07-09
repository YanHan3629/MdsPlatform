package com.fwdrobo.sirius.port;

/**
 * WS 推送端口
 */
public interface WsPushPort {

    /**
     * 广播到所有连接
     */
    void broadcast(String json);

    /**
     * 精准推送到指定 serial_number 的会话组
     */
    void sendToSerial(String serialNumber, String json);
}
