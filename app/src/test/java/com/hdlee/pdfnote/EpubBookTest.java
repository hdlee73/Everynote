package com.hdlee.pdfnote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EpubBookTest {
    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name)); z.write(text.getBytes(StandardCharsets.UTF_8)); z.closeEntry();
    }

    private static File sample() throws Exception {
        File f = File.createTempFile("sample", ".epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "mimetype", "application/epub+zip");
            put(z, "META-INF/container.xml", "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(z, "OEBPS/content.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>테스트 책</dc:title></metadata>"
                + "<manifest><item id=\"n\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"a\" href=\"text/a%20b.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"text/b.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                + "<spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>");
            put(z, "OEBPS/nav.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol><li><a href=\"text/a%20b.xhtml\">첫 장</a></li><li><a href=\"text/b.xhtml#x\">둘째 장</a></li></ol></nav></body></html>");
            put(z, "OEBPS/text/a b.xhtml", "<html><head><title>no</title><style>p{color:red}</style></head><body><h1>제목</h1><p>본문 <b>강조</b></p></body></html>");
            put(z, "OEBPS/text/b.xhtml", "<html><body><p id=\"x\">둘째</p></body></html>");
        }
        return f;
    }

    @Test public void readsSpineTitleAndToc() throws Exception {
        try (EpubBook book = EpubBook.open(sample())) {
            assertEquals("테스트 책", book.title);
            assertEquals(2, book.spine.size());
            assertEquals("OEBPS/text/a b.xhtml", book.spine.get(0));
            assertEquals(2, book.toc.size());
            assertEquals("첫 장", book.chapterTitle(0));
            assertEquals("둘째 장", book.chapterTitle(1));
            assertNotNull(book.read("oebps/TEXT/b.xhtml"));
        }
    }

    @Test public void resolvesRelativePaths() {
        assertEquals("OEBPS/images/c.png", EpubBook.resolve("OEBPS/text/", "../images/c.png"));
        assertEquals("a/b c.png", EpubBook.resolve("x/", "/a/b%20c.png"));
    }

    @Test public void chapterTextDropsHeadAndStyle() throws Exception {
        try (EpubBook book = EpubBook.open(sample())) {
            String text = EpubPdf.chapterText(book, book.spine.get(0), 300, 400).toString();
            assertTrue(text, text.contains("제목") && text.contains("본문 강조"));
            assertTrue(text, !text.contains("color:red") && !text.contains("no"));
        }
    }

    @Test public void settingsCssFollowsValues() {
        EpubSettings s = new EpubSettings(org.robolectric.RuntimeEnvironment.getApplication());
        s.line = 20; s.margin = 8; s.font = 1; s.theme = 3;
        String css = s.css(30, 40);
        assertTrue(css, css.contains("line-height:2.0") && css.contains("padding:30px 20px 40px") && css.contains("column-width:calc(100vw - 40px)") && css.contains("serif") && css.contains("#000000"));
    }
}
