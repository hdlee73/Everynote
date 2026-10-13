package com.hdlee.pdfnote;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Looks up the newest published release on GitHub (pre-releases such as the PR test builds are ignored by /releases/latest). */
final class UpdateChecker {
    static final String API = "https://api.github.com/repos/hdlee73/Everynote_And/releases/latest";
    static final String TRUSTED_PREFIX = "https://github.com/hdlee73/Everynote_And/";
    static final String RELEASES_PAGE = TRUSTED_PREFIX + "releases";
    static final class Release { String version, url, page, notes; }

    /** Only links into this repository's GitHub releases are ever opened; anything else is dropped. */
    static boolean isTrustedUrl(String u) { return u != null && u.startsWith(TRUSTED_PREFIX) && !u.contains("..") && u.indexOf('@') < 0; }

    /** Numeric comparison of dotted versions ("v1.35.0" vs "1.36.0"): >0 when a is newer than b. */
    static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) { int d = (i < x.length ? x[i] : 0) - (i < y.length ? y[i] : 0); if (d != 0) return d; }
        return 0;
    }
    private static int[] parts(String v) {
        String[] s = v.trim().replaceFirst("^[vV]", "").split("[.-]"); int[] out = new int[s.length];
        for (int i = 0; i < s.length; i++) { try { out[i] = Integer.parseInt(s[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { out[i] = 0; } }
        return out;
    }

    /** Blocking; run on a background thread. Returns the latest release (apk url may be null). */
    static Release latest() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "Everynote");
        try {
            if (c.getResponseCode() != 200) throw new IOException("업데이트 서버 응답 " + c.getResponseCode());
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; try (InputStream in = c.getInputStream()) { while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > 2_000_000) break; } }
            JSONObject j = new JSONObject(o.toString("UTF-8")); Release r = new Release();
            r.version = j.optString("tag_name").replaceFirst("^[vV]", ""); r.page = j.optString("html_url"); if (!isTrustedUrl(r.page)) r.page = RELEASES_PAGE; r.notes = j.optString("body");
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) { JSONObject a = assets.getJSONObject(i); if (a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) { String u = a.optString("browser_download_url"); if (isTrustedUrl(u)) { r.url = u; break; } } }
            if (r.version.isEmpty()) throw new IOException("릴리스 정보를 읽을 수 없습니다");
            return r;
        } catch (org.json.JSONException e) { throw new IOException("릴리스 정보를 읽을 수 없습니다"); }
        finally { c.disconnect(); }
    }
}
