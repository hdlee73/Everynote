package com.hdlee.pdfnote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EpubStoreTest {
    private static File source(String content) throws Exception {
        File f = File.createTempFile("src", ".epub");
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(content.getBytes("UTF-8")); }
        return f;
    }

    @Test public void addsOnceAndKeepsDifferentBooksWithSameName() throws Exception {
        EpubStore store = new EpubStore(RuntimeEnvironment.getApplication());
        File a = store.add(RuntimeEnvironment.getApplication(), Uri.fromFile(source("one")), "책.epub");
        File again = store.add(RuntimeEnvironment.getApplication(), Uri.fromFile(source("one")), "책.epub");
        File other = store.add(RuntimeEnvironment.getApplication(), Uri.fromFile(source("two!")), "책.epub");
        assertEquals(a, again);
        assertTrue(!a.equals(other) && other.getName().startsWith("책 (1)"));
        assertTrue(store.list().contains(a) && store.list().contains(other));
        assertTrue(store.delete(other));
        assertFalse(other.exists());
    }

    @Test public void remembersInfoByNameAndSize() throws Exception {
        EpubStore store = new EpubStore(RuntimeEnvironment.getApplication());
        File f = store.add(RuntimeEnvironment.getApplication(), Uri.fromFile(source("info")), "info.epub");
        store.info(EpubStore.key(f.getName(), f.length()), "제목", 42);
        String[] info = store.info(f);
        assertEquals("제목", info[0]);
        assertEquals("42", info[1]);
        store.delete(f);
    }
}
