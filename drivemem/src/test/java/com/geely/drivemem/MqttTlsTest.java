package com.geely.drivemem;

import com.geely.drivemem.net.MqttTls;

import org.junit.Test;

import javax.net.ssl.SSLSocketFactory;

import static org.junit.Assert.*;

public class MqttTlsTest {

    @Test
    public void testIsTlsSingleUri() {
        assertTrue(MqttTls.isTls("ssl://example.com:8883"));
        assertTrue(MqttTls.isTls("tls://example.com:8883"));
        assertTrue(MqttTls.isTls("wss://example.com:8443/mqtt"));
        assertTrue(MqttTls.isTls("SSL://EXAMPLE.COM:8883"));
        assertTrue(MqttTls.isTls("  wss://example.com:8443/mqtt  "));

        assertFalse(MqttTls.isTls("tcp://192.168.0.8:1883"));
        assertFalse(MqttTls.isTls("ws://example.com:1884/mqtt"));
        assertFalse(MqttTls.isTls(""));
        assertFalse(MqttTls.isTls((String) null));
    }

    @Test
    public void testIsTlsUriArray() {
        assertTrue(MqttTls.isTls(new String[]{"tcp://192.168.0.8:1883", "ssl://remote.com:8883"}));
        assertTrue(MqttTls.isTls(new String[]{"wss://mosquitto.tail8e9bb2.ts.net:8443/mqtt"}));
        assertFalse(MqttTls.isTls(new String[]{"tcp://192.168.0.8:1883", "tcp://10.0.0.1:1883"}));
        assertFalse(MqttTls.isTls((String[]) null));
        assertFalse(MqttTls.isTls(new String[0]));
    }

    @Test
    public void testTlsSocketFactoryWrapperInstantiation() {
        SSLSocketFactory defaultFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        MqttTls.TlsSocketFactoryWrapper wrapper = new MqttTls.TlsSocketFactoryWrapper(defaultFactory);
        assertNotNull(wrapper);
        assertNotNull(wrapper.getDefaultCipherSuites());
        assertNotNull(wrapper.getSupportedCipherSuites());
    }
}
