package com.hdlee.pdfnote;

import static org.junit.Assert.*;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The PC compares both libraries and sends what is newer: folders are listed and created, and a document that is open here is saved before it is read and closed / reopened around a replacement. */
public class DeviceSyncTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private File lib, assets;

    private DeviceSync server() throws Exception {
        lib = tmp.newFolder("lib"); assets = tmp.newFolder("assets");
        DeviceSync sync = new DeviceSync(new DeviceSync.Host() {
            public String listLibrary() { return "[]"; }
            public String listFolders() { return "[\"a\",\"a/b\"]"; }
            public boolean makeFolder(String path) { log.add("folder " + path); return !path.contains(".."); }
            public File libraryFile(String path) { return path == null || path.contains("..") || !path.endsWith(".pdf") ? null : new File(lib, path); }
            public String exportNote(String path) { return "{\"format\":\"PDF Note annotations v2\"}"; }
            public void importNote(String path, String json) { log.add("importNote " + path); }
            public File assetFile(String name) { return new File(assets, name); }
            public void onActivity(String message) { }
            public void beforeRead(String path) { log.add("beforeRead " + path); }
            public void beforeReplace(String path) { log.add("beforeReplace " + path); }
            public void afterReplace(String path) { log.add("afterReplace " + path); }
        });
        sync.start();
        return sync;
    }

    private HttpURLConnection open(DeviceSync sync, String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + sync.port() + path).openConnection();
        c.setRequestMethod(method); c.setRequestProperty("X-Code", sync.code());
        return c;
    }

    private static String body(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    private int put(DeviceSync sync, String path, byte[] data) throws Exception {
        HttpURLConnection c = open(sync, "PUT", path); c.setDoOutput(true); c.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = c.getOutputStream()) { out.write(data); }
        return c.getResponseCode();
    }

    @Test public void listsTheFoldersOfTheLibrary() throws Exception {
        DeviceSync sync = server();
        try { assertEquals("[\"a\",\"a/b\"]", body(open(sync, "GET", "/v1/folders"))); } finally { sync.stop(); }
    }

    @Test public void createsAFolderAndRefusesABadPath() throws Exception {
        DeviceSync sync = server();
        try {
            assertEquals(200, put(sync, "/v1/folder?p=" + URLEncoder.encode("새 폴더/하위", "UTF-8"), new byte[0]));
            assertTrue(log.contains("folder 새 폴더/하위"));
            assertEquals(400, put(sync, "/v1/folder?p=" + URLEncoder.encode("../x", "UTF-8"), new byte[0]));
        } finally { sync.stop(); }
    }

    @Test public void anOpenDocumentIsSavedBeforeItIsReadAndClosedAroundAReplacement() throws Exception {
        DeviceSync sync = server();
        try {
            File pdf = new File(lib, "a.pdf"); try (FileOutputStream f = new FileOutputStream(pdf)) { f.write("%PDF-old".getBytes(StandardCharsets.UTF_8)); }
            HttpURLConnection read = open(sync, "GET", "/v1/pdf?p=a.pdf");
            assertEquals("%PDF-old", body(read));
            assertEquals(Arrays.asList("beforeRead a.pdf"), log);
            log.clear();
            assertEquals(200, put(sync, "/v1/pdf?p=a.pdf", "%PDF-new".getBytes(StandardCharsets.UTF_8)));
            assertEquals("the document is closed before its PDF is replaced", Arrays.asList("beforeReplace a.pdf"), log);
            assertEquals(200, put(sync, "/v1/note?p=a.pdf", "{\"format\":\"PDF Note annotations v2\"}".getBytes(StandardCharsets.UTF_8)));
            assertEquals("the notes are the last thing sent, then the document is opened again", Arrays.asList("beforeReplace a.pdf", "importNote a.pdf", "afterReplace a.pdf"), log);
        } finally { sync.stop(); }
    }
}
