package com.hdlee.pdfnote;

import static org.junit.Assert.*;
import org.junit.Test;

public class UpdateSecurityTest {
    @Test public void onlyThisRepositoryReleaseLinksAreTrusted() {
        assertTrue(UpdateChecker.isTrustedUrl("https://github.com/hdlee73/Everynote_And/releases/download/v1.57.0/Everynote-v1.57.0.apk"));
        assertTrue(UpdateChecker.isTrustedUrl(UpdateChecker.RELEASES_PAGE));
        assertFalse(UpdateChecker.isTrustedUrl(null));
        assertFalse(UpdateChecker.isTrustedUrl("http://github.com/hdlee73/Everynote_And/releases"));
        assertFalse(UpdateChecker.isTrustedUrl("https://github.com/evil/Everynote_And/releases/download/v9/x.apk"));
        assertFalse(UpdateChecker.isTrustedUrl("https://github.com/hdlee73/Everynote_And/../../evil/x.apk"));
        assertFalse(UpdateChecker.isTrustedUrl("https://github.com/hdlee73/Everynote_And/@evil.example/x.apk"));
        assertFalse(UpdateChecker.isTrustedUrl("https://evil.example/https://github.com/hdlee73/Everynote_And/"));
    }

    @Test public void certificateFingerprintIsColonSeparatedSha256() {
        // SHA-256("abc")
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", CertInfo.sha256("abc".getBytes()));
    }
}
