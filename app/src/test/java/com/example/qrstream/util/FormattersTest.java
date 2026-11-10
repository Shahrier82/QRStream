package com.example.qrstream.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for {@link Formatters}. */
public class FormattersTest {

    @BeforeClass
    public static void useEnglishLocale() {
        Locale.setDefault(Locale.US);
    }

    @Test
    public void sizes() {
        assertEquals("0 B", Formatters.formatSize(-5));
        assertEquals("512 B", Formatters.formatSize(512));
        assertEquals("1.0 KB", Formatters.formatSize(1024));
        assertEquals("200.0 KB", Formatters.formatSize(200 * 1024));
        assertEquals("1.5 MB", Formatters.formatSize(1536 * 1024));
    }

    @Test
    public void durations() {
        assertEquals("850 ms", Formatters.formatDuration(850));
        assertEquals("12.3 s", Formatters.formatDuration(12300));
        assertEquals("2 min 05 s", Formatters.formatDuration(125000));
    }

    @Test
    public void speedAndPercent() {
        assertEquals("1.0 KB/s", Formatters.formatSpeed(1024));
        assertEquals("0 B/s", Formatters.formatSpeed(Double.NaN));
        assertEquals("37%", Formatters.formatPercent(37.0));
        assertEquals("12.5%", Formatters.formatPercent(12.5));
    }

    @Test
    public void hashAbbreviation() {
        assertEquals("-", Formatters.abbreviateHash(null));
        assertEquals("abc", Formatters.abbreviateHash("abc"));
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals("01234567...89abcdef", Formatters.abbreviateHash(hash));
    }

    @Test
    public void sanitizeRemovesPathsAndDangerousCharacters() {
        assertEquals("passwd", Formatters.sanitizeFileName("../../etc/passwd"));
        assertEquals("evil.txt", Formatters.sanitizeFileName("C:\\Windows\\evil.txt"));
        assertEquals("hidden", Formatters.sanitizeFileName("...hidden"));
        assertEquals("a_b_c.txt", Formatters.sanitizeFileName("a:b*c.txt"));
        assertEquals("received_file", Formatters.sanitizeFileName(null));
        assertEquals("received_file", Formatters.sanitizeFileName("   "));
        assertEquals("received_file", Formatters.sanitizeFileName("/"));
        assertEquals("photo (1).jpg", Formatters.sanitizeFileName("photo (1).jpg"));
    }

    @Test
    public void sanitizeLimitsLengthAndKeepsExtension() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('a');
        }
        String result = Formatters.sanitizeFileName(sb + ".pdf");
        assertEquals(100, result.length());
        assertTrue(result.endsWith(".pdf"));
        assertFalse(result.contains("/"));
    }

    @Test
    public void extensions() {
        assertEquals("pdf", Formatters.extensionOf("Report.PDF"));
        assertEquals("", Formatters.extensionOf("noext"));
        assertEquals("", Formatters.extensionOf("trailing."));
        assertEquals("", Formatters.extensionOf(null));
    }
}
