package com.geely.drivemem.net;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class MqttReporterOptionsTest {

    // Paho's own reconnect timer outlived every dropped client, and the
    // survivors kicked each other off the broker under one client id
    // ("session taken over", 2026-09-28). It must stay off.
    @Test public void pahoAutomaticReconnectIsOff() {
        for (String target : new String[]{"tcp://lan-broker:1883", "wss://broker.example:443/mqtt"}) {
            MqttConnectOptions o = MqttReporter.connectOptions(target, "u", "p");
            assertFalse(target, o.isAutomaticReconnect());
        }
    }

    @Test public void lanFailsFastRemoteGetsLonger() {
        assertEquals(4, MqttReporter.connectOptions("tcp://lan-broker:1883", null, null).getConnectionTimeout());
        assertEquals(10, MqttReporter.connectOptions("wss://broker.example:443/mqtt", null, null).getConnectionTimeout());
    }
}
