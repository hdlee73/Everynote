package com.hdlee.pdfnote;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;

/** SHA-256 fingerprint of the certificate this installed app is signed with (shown in app info so it can be compared with the release page). */
final class CertInfo {
    private CertInfo() {}

    /** "AB:CD:…" upper-case colon-separated SHA-256 of a DER certificate. */
    static String sha256(byte[] der) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < d.length; i++) { if (i > 0) b.append(':'); b.append(String.format(java.util.Locale.ROOT, "%02X", d[i] & 0xff)); }
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    /** Fingerprint of the current signing certificate, or "" when it cannot be read. */
    @SuppressWarnings("deprecation")
    static String current(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageInfo p = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (p.signingInfo == null) return "";
                sigs = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners() : p.signingInfo.getSigningCertificateHistory();
                if (!p.signingInfo.hasMultipleSigners() && sigs != null && sigs.length > 0) sigs = new Signature[] { sigs[sigs.length - 1] };
            } else {
                sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            return sigs == null || sigs.length == 0 ? "" : sha256(sigs[0].toByteArray());
        } catch (Exception e) { return ""; }
    }
}
