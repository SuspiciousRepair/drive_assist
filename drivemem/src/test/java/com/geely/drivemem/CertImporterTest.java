package com.geely.drivemem;

import com.geely.drivemem.net.CertImporter;
import com.geely.drivemem.net.MqttTls;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Date;

import static org.junit.Assert.*;

public class CertImporterTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File tempDir;

    private static final String SAMPLE_PEM_CA =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIDBDCCAeygAwIBAgIJALtpOH7BmuiKMA0GCSqGSIb3DQEBCwUAMDAxCzAJBgNV\n" +
            "BAYTAkJSMQ4wDAYDVQQKEwVHZWVseTERMA8GA1UEAxMIdGVzdC1jYXIwHhcNMjYw\n" +
            "OTA5MTE0MzAwWhcNMjcwOTA5MTE0MzAwWjAwMQswCQYDVQQGEwJCUjEOMAwGA1UE\n" +
            "ChMFR2VlbHkxETAPBgNVBAMTCHRlc3QtY2FyMIIBIjANBgkqhkiG9w0BAQEFAAOC\n" +
            "AQ8AMIIBCgKCAQEAw/maLXbj2Icxx3LJxNgwh9vRWpicoIkEkRiyl+2sG1Z3/m7m\n" +
            "qf7f4aBYZ2iooh/viWESlfZo+hUdUl9px3iBM0UBGCzSwh8+t4OOycJiRbJAzYah\n" +
            "CdJjNwWionzkP1Eazj3oYsmazh9slk9dONL2EwTYVEUi3p3bRGCj2qc3KfijsZnQ\n" +
            "0B6ksI9B0g0BJ7DEzGHrOX27lrMTCiPBlujAlEqV75WiAoo+zlPIcPEc3HYtlrje\n" +
            "KT8YO98MRN+fgaI1hBJ0g6Q1aUhpao3ItKPxD25Fxpcg7tJDv3XOP4M4FOwTMT4U\n" +
            "b7J03VTPva1LyR8H06Eyli65XWRP36YNDnD0cwIDAQABoyEwHzAdBgNVHQ4EFgQU\n" +
            "RCyJ4HmtZihgBWP3IT2FbK00ZSAwDQYJKoZIhvcNAQELBQADggEBAF6PjJy2fSNF\n" +
            "0mhG4lDTWTU0F2N/g/ME+dqJVXz1wF+CW/47uK4/C2RcBTeWrbjpfaBUiNsCIpAs\n" +
            "MKdlcoGwMc7CBHjMe5b0FCsJLiiAdZBX6aPU+ABws65FofqMi58FZVCx++HDCoTC\n" +
            "6Bct27gaN9BWwptiXoE7qcM7A+FJBROza4iCAg6Fb0HB2IiFbJxCcI+m/nx+gapV\n" +
            "2L+uFSFZ3BBW9O1bBd9Koklq/NQ+U4dVF+8R1i5LHgQC1iSxBuDS5Bc6cJDVJu4G\n" +
            "+iRTrIZMSLVEtoygtFOsN95Bi41wbXA1BsWogqWXucBuAA076qWsHcRfU1LDL+ZJ\n" +
            "5w7nEgHtqs8=\n" +
            "-----END CERTIFICATE-----\n";

    private static final String SAMPLE_PKCS12_BASE64 =
            "MIIKNAIBAzCCCd4GCSqGSIb3DQEHAaCCCc8EggnLMIIJxzCCBa4GCSqGSIb3DQEHAaCCBZ8EggWb" +
            "MIIFlzCCBZMGCyqGSIb3DQEMCgECoIIFQDCCBTwwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUM" +
            "MCsEFDF/Yo1ob0jpuzCdh69lqzCuPtAKAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQB" +
            "KgQQ/WNWL7ifSkXxs7jmUQu8wQSCBNAtdLUZIoPrfLNPkl7j2yhXpME0oJuBTz5iyVTfZkcT8fsL" +
            "FEp2yWyOJgUqIZ2NsPqA0bdJGjUyWXscpnTsO9GwcFJKhbp2N725RGJXCZqFftvY5aR120Sn2MBs" +
            "KG+3p9sZvmU/QqD2ECGu5xjuvEVBaPe3H/yaSG7d+CGJM6mgeZwHl3iItrCcfSEBXNsNe6WfaTPl" +
            "EydTXVDBL5KAkZbaO6rNVGZAoqzXcY9EFqs5oVvwiOKdQ12Z+FphyyexTOW0QAmg58EB7BQpH0Cg" +
            "ikrXP9mJLLbO33/9OP4AIuZI45SuM/aQkhaoH7TjHnoHTgAKVaecjX2cG6mXyRCyHllD3oCg03Ke" +
            "NH0p+esZLxqck2MBEyz4FvTW9qxeEQw1anklXPOSJ2RaDLR/VLqgzEyxiZJbPxvGoOaXziWZfhLs" +
            "YBnEJuFeSGUlC+hVveIangejSpJz8/oBpEIBKslIQkk+YKcUHJkqgwLZ50URKDIzXgahFZ4/gjVX" +
            "rP9lYC7mvi2vOR7Mwy0gE504nLSFbkTCaXjUL33OUftao5X/DkgQBj3vGJTrEg3THBpZ0qz6pOea" +
            "+htKmIN+/jGvctxOEJG/wLPHLRwRHAXkQg1Lk+RIr+0O76hfCQxSBH8RoodZVI5UXkO6HlnAYsSb" +
            "ARrQH4cc0ixfpxs+hDgBN4js8CBIAdCGB9rdZ8YK7vj6muQQAfATdYoO06U6PGk1j6O2eL0Hvk6s" +
            "eg7YCdKkb0LldNfHziESW76WmYD92ziG+36gBh/NgicR1I6d+T9Rjxtjp1RSJ4Ra2ooWpLveVv9P" +
            "OoGUpGXZIEvXNPmkjn+MXplLFLsxR0d5izm33XAVVLTxjEBZ/BvAXmTi1mqDoeFBIc5bKypEFtrL" +
            "I+NX4FLk5Rb+PVGClHNUo2+64M7fq8CoKjKEPMwz9VIjN4MEb74vUrEfEJmfF4g0aezNKZCCUrJ" +
            "pijyoHvsF1WtVAtk+kp+3n/0LwBQm/MOrDxoSnKiBUWCdtaGiIUiWpbkiGlNkrh40nA9kslajEA/X" +
            "Gn/J4s9cnQTN0/ibcs30VHmP/rd/CKlcggODQKPw7TQpSq1G17t12w/ds9Jei2/kgaJy1GEgzO26" +
            "L6ckDPaKnQfU1PEO9HzxS+8sIP6dAtmDfUIEp1AVbW0bmDPzeROAbzIK3b7kuO+MfBRtO1/7K08t" +
            "qhhMeKD12AGtGJp3d4rUq/rLAF3QYg5ucfzG805BBRoWGPD+ZrF3YfpHcQ9Dd9GRnH23IoEGFyO9" +
            "RqSScPqJsyYqs97lz26a0m+NtGtEr05wuS4mXWDsDbVwwJX94vvCi+ZaotfnkAcAXzgcQTKlVUpL" +
            "2GLQgVWIfUS7vNX+Z99L4vmQ7a4ZnK9LtIUYpK8lTh/Vz8xC8jzYQ2OomqwaALdzZVfYYynnnGIe" +
            "C8dtaZqdZXMVeeQ5Uj7gDKp64eHU48UVrwM+9ePvzRy0Rj96aTPessO2CsdJf58Gm6gya2L1eO0u" +
            "cruGEZ0RnG10ryo5bwTmO8MuV2JqFX4AcVWTyNfRH1Rnln95hofM44S5g67gUikFstasz7EjFLw7" +
            "TFk+Ba1OrFrzFWvm1zRv3o7rrHbpEBR4qNihgOgbUMbWp5m75Yg7V0z7Fx39hGfkamoBuAi9AQoa" +
            "iTFAMBsGCSqGSIb3DQEJFDEOHgwAYwBsAGkAZQBuAHQwIQYJKoZIhvcNAQkVMRQEElRpbWUgMTc4" +
            "ODk1NDE4MDA0NTCCBBEGCSqGSIb3DQEHBqCCBAIwggP+AgEAMIID9wYJKoZIhvcNAQcBMGYGCSqG" +
            "SIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBQfiT76Fh7wcpBJUDqzDaih2PG2UwICJxACASAwDAYI" +
            "KoZIhvcNAgkFADAdBglghkgBZQMEASoEEImJSBG1Hv3GFeArrRivAwqAggOAyrClE1souLfeto1t" +
            "036oAnnY0SjL/Rw/jsfhfxbrzggt3ef9C/qJ5jp3i58GHVmF4A+zVDkV01j5KtxjBGV2cdSUNCLf" +
            "J1F6y0LHfnWwGu01bBWEkSBWANQwW56fQkNd/+zQINepSaU5YxGkxlKZmQqIzoB4HHMpjjjOafZF" +
            "S6YZSNvEMlU8R/aaxGNv4AOpYb5h77v+KTXN684F3doFzRyLUiz40NSflUQ8V4IN9QZ57ikKabQM" +
            "7MItkXNorVOzVJ0vx5Wf+0Kvn+C/e49WD76gNpnlQPSybuoRYSTV1N+HF/gx87unQHiSPAVwRBff" +
            "GWMTCBg6HKylArWzCthsEbgLSwJLLAWvMWmes8o00p42PC9MX80T3XFLj9yKN02JFNEhlxZHyJcV" +
            "MOwFo/BllaxNWXfgjVVGHNFE62nQO8XkoBDUUZJEaNsceDtEtLzQbNtqI3zxJnt1R3FsCYpaY+r7" +
            "ZJ/AnlweilyIzTsK/XzsvQhFwkJw1ix55pmREkXGVUbGGEfnpVaGxRGg3HZe/zY/iVpUZT1xH2/T" +
            "cbYvdgEXP1RZrwBGKLPRzKNapqZaMk1recSHU8j6UnpKUyg5wEwpmDXQ9d2hfYYLQ1yjxnIncYjH" +
            "ZlgVF7gahojtJwIo5s5m3Eojyeol091qwv4RWh+eK3c1DZ5SDQoFTw5h6+G6iZTbVmrQOJAC7Z4q" +
            "A6lzwLtb19L8lqpx9kcywkkEwaFz0zFdq46TiDZKEGDTudrmJ6qukIYV16rKzY/TPBAtnaEmv32P" +
            "37VAZjrAPcDBYDidfGTgiraq66Bd3goBISSIDHQuYxO1sxME8j6Dv0ZLXizBWSjNBGRGFeQ4wmba" +
            "TYOgs6RgaQkDqMrSS8WQFeBKoaWdPyLHwD1p1A3n4Up7hHYrg6BJnXcxqKrLtPmDNGdJ9Wy+lVb6" +
            "/Gg/2yi+CQCjPJL9o1KPgvkv01UWIPJI9OPBZ+vtrYni9qp2XcYel2Glb7+DxDifgwxMlHBHUlQr" +
            "YsWUUsbnqWtfsUiq+sHqEYg9Htpb/suQ5Hy9mqhrSdHV7isaZt6evtWpuGbku30ApQJfiQznhXWV" +
            "rXwtqblOAE59KvyA1cDd2ddc8QxZJbShjkPDNEuOt9SVrPDv9QBP6Sd15tOPLFPdxtjck9gCKqG6" +
            "Z4C2LuyBoMqZDymx+ZfY42wSK64n5YHk/+Dm6hUwTTAxMA0GCWCGSAFlAwQCAQUABCBKqv1K+krD" +
            "5XYTninATLqqfRzGMrTCCiCTLwBMBVkRDwQUb6RTsx+O+AC0QCGsZQb7kzOH1qUCAicQ";

    @Before
    public void setUp() {
        tempDir = tempFolder.getRoot();
    }

    @Test
    public void testExtractCN() {
        assertEquals("drivemem-geely", CertImporter.extractCN("CN=drivemem-geely, O=Geely, C=BR"));
        assertEquals("My CA", CertImporter.extractCN("O=Home, CN=My CA"));
        assertEquals("PlainName", CertImporter.extractCN("cn=PlainName"));
        assertEquals("NoCNHere", CertImporter.extractCN("NoCNHere"));
        assertEquals("Unknown", CertImporter.extractCN(null));
    }

    @Test
    public void testCertInfoExpiry() {
        CertImporter.CertInfo info = new CertImporter.CertInfo(
                "test", "CN=client", "CN=ca",
                new Date(System.currentTimeMillis() - 100000),
                new Date(System.currentTimeMillis() + 100000000),
                true, 1);
        assertFalse(info.isExpired);
        assertEquals("client", info.getCommonName());
        assertNotNull(info.getFormattedExpiry());

        CertImporter.CertInfo expired = new CertImporter.CertInfo(
                "test", "CN=expired-client", "CN=ca",
                new Date(System.currentTimeMillis() - 200000),
                new Date(System.currentTimeMillis() - 100000),
                true, 1);
        assertTrue(expired.isExpired);
        assertEquals("expired-client", expired.getCommonName());
    }

    @Test
    public void testImportNullOrEmpty() {
        CertImporter.ImportResult nullRes = CertImporter.importData(tempDir, null, null);
        assertFalse(nullRes.success);

        CertImporter.ImportResult emptyRes = CertImporter.importData(tempDir, new byte[0], null);
        assertFalse(emptyRes.success);
    }

    @Test
    public void testImportCorruptData() {
        byte[] garbage = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        CertImporter.ImportResult res = CertImporter.importData(tempDir, garbage, null);
        assertFalse(res.success);
    }

    @Test
    public void testImportCaPem() {
        byte[] pemBytes = SAMPLE_PEM_CA.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        CertImporter.ImportResult res = CertImporter.importData(tempDir, pemBytes, null);
        assertTrue("Importing CA PEM should succeed", res.success);
        assertFalse("CA PEM does not require a password", res.passwordRequired);
        assertNotNull(res.certInfo);
        assertEquals("test-car", res.certInfo.getCommonName());
        assertFalse("CA cert should not have private key", res.certInfo.hasPrivateKey);

        assertTrue("hasCustomCa should be true", CertImporter.hasCustomCa(tempDir));
        CertImporter.CertInfo caInfo = CertImporter.getCaCertInfo(tempDir);
        assertNotNull(caInfo);
        assertEquals("test-car", caInfo.getCommonName());

        assertTrue("removeCustomCaCert should succeed", CertImporter.removeCustomCaCert(tempDir));
        assertFalse("hasCustomCa should be false after removal", CertImporter.hasCustomCa(tempDir));
    }

    @Test
    public void testImportPkcs12PasswordProtected() {
        byte[] p12Bytes = CertImporter.decodeBase64(SAMPLE_PKCS12_BASE64);
        assertNotNull(p12Bytes);

        // 1. Attempt with empty password -> should indicate password required
        CertImporter.ImportResult reqPass = CertImporter.importData(tempDir, p12Bytes, "");
        assertFalse("Should fail without password", reqPass.success);
        assertTrue("Should indicate password required", reqPass.passwordRequired);

        // 2. Attempt with wrong password -> should fail
        CertImporter.ImportResult wrongPass = CertImporter.importData(tempDir, p12Bytes, "wrongpass");
        assertFalse("Should fail with incorrect password", wrongPass.success);
        assertFalse("Should not flag passwordRequired when wrong password given", wrongPass.passwordRequired);

        // 3. Attempt with correct password -> should succeed
        CertImporter.ImportResult ok = CertImporter.importData(tempDir, p12Bytes, "password123");
        assertTrue("Should succeed with correct password", ok.success);
        assertNotNull(ok.certInfo);
        assertEquals("test-car", ok.certInfo.getCommonName());
        assertTrue("Client bundle should have private key", ok.certInfo.hasPrivateKey);

        // 4. Verify file was saved as unencrypted mqtt_client.p12 (can be inspected without password)
        File p12File = new File(tempDir, MqttTls.CLIENT_P12);
        assertTrue("File mqtt_client.p12 must exist", p12File.isFile());

        CertImporter.CertInfo clientInfo = CertImporter.getClientCertInfo(tempDir);
        assertNotNull("Inspecting client cert without password must succeed", clientInfo);
        assertEquals("test-car", clientInfo.getCommonName());

        // 5. Remove client certificate
        assertTrue("removeClientCert should succeed", CertImporter.removeClientCert(tempDir));
        assertNull("getClientCertInfo should return null after removal", CertImporter.getClientCertInfo(tempDir));
    }

    @Test
    public void testBase64EncodingDecoding() {
        byte[] sample = "DriveAssist-Security-Token-12345".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String b64 = CertImporter.encodeBase64(sample);
        assertNotNull(b64);
        assertFalse(b64.isEmpty());

        byte[] decoded = CertImporter.decodeBase64(b64);
        assertArrayEquals(sample, decoded);
    }
}
