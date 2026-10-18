package com.hdlee.pdfnote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** The PC finds the phone by a UDP probe; the answer names the phone and its port but never the code. */
public class DeviceSyncDiscoveryTest {
    private static DeviceSync server() {
        return new DeviceSync(new DeviceSync.Host() {
            public String listLibrary() { return "[]"; }
            public boolean isOpen(String path) { return false; }
            public File libraryFile(String path) { return null; }
            public String exportNote(String path) { return null; }
            public void importNote(String path, String json) { }
            public File assetFile(String name) { return null; }
            public void onActivity(String message) { }
            @Override public String deviceName() { return "SM-\"S918N\""; }
            @Override public String deviceId() { return "id-1"; }
            @Override public String appVersion() { return "1.1.0"; }
        });
    }

    @Test public void answersProbeWithPortButNotCode() throws Exception {
        DeviceSync sync = server(); sync.start();
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);
            byte[] probe = DeviceSync.PROBE.getBytes(StandardCharsets.UTF_8);
            client.send(new DatagramPacket(probe, probe.length, InetAddress.getLoopbackAddress(), DeviceSync.DISCOVERY_PORT));
            DatagramPacket p = new DatagramPacket(new byte[1024], 1024); client.receive(p);
            String answer = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
            assertTrue(answer, answer.contains("\"port\":" + sync.port()));
            assertTrue(answer, answer.contains("\"name\":\"SM-\\\"S918N\\\"\""));
            assertTrue(answer, answer.contains("\"id\":\"id-1\""));
            assertFalse("코드는 알리지 않습니다", answer.contains(sync.code()));
        } finally { sync.stop(); }
    }

    @Test public void infoNeedsTheCode() throws Exception {
        DeviceSync sync = server(); sync.start();
        try {
            HttpURLConnection bad = (HttpURLConnection) new URL("http://127.0.0.1:" + sync.port() + "/v1/info").openConnection();
            bad.setRequestProperty("X-Code", "x"); assertEquals(403, bad.getResponseCode());
            HttpURLConnection ok = (HttpURLConnection) new URL("http://127.0.0.1:" + sync.port() + "/v1/info").openConnection();
            ok.setRequestProperty("X-Code", sync.code()); assertEquals(200, ok.getResponseCode());
            try (InputStream in = ok.getInputStream()) {
                String body = new String(readAll(in), StandardCharsets.UTF_8);
                assertTrue(body, body.startsWith("{\"app\":\"HDnotes\",\"platform\":\"android\""));
            }
        } finally { sync.stop(); }
    }

    @Test public void ignoresOtherPackets() throws Exception {
        DeviceSync sync = server(); sync.start();
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(700);
            byte[] junk = "hello".getBytes(StandardCharsets.UTF_8);
            client.send(new DatagramPacket(junk, junk.length, InetAddress.getLoopbackAddress(), DeviceSync.DISCOVERY_PORT));
            try { client.receive(new DatagramPacket(new byte[64], 64)); fail("no answer expected"); } catch (SocketTimeoutException expected) { }
        } finally { sync.stop(); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toByteArray();
    }
}
