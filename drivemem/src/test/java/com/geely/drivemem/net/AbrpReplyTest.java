package com.geely.drivemem.net;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// ABRP's reply body goes into abrp-attempt.log, which is tab-separated with
// one attempt per line. These pin that the reply can never break that shape.
public class AbrpReplyTest {

    private static String read(String body) throws Exception {
        return AbrpUploader.readReply(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void keepsAShortReplyAsIs() throws Exception {
        assertEquals("{\"status\": \"ok\"}", read("{\"status\": \"ok\"}"));
    }

    @Test public void foldsTabsAndLineBreaksIntoOneLine() throws Exception {
        String out = read("{\n\t\"status\": \"error\",\r\n\t\"message\": \"too old\"\n}\n");
        assertFalse(out.contains("\t"));
        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\r"));
        assertEquals("{ \"status\": \"error\", \"message\": \"too old\" }", out);
    }

    @Test public void capsALongReply() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append('x');
        String out = read(big.toString());
        assertEquals(AbrpUploader.REPLY_CAP + 3, out.length());
        assertTrue(out.endsWith("..."));
    }

    @Test public void noBodyOrBlankBodyIsMarkedEmpty() throws Exception {
        assertEquals("(empty)", AbrpUploader.readReply(null));
        assertEquals("(empty)", read(""));
        assertEquals("(empty)", read("  \n\t "));
    }
}
