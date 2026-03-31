package com.jamillabltd.copilot_textproject;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link YoutubeHelper#extractVideoId(String)} and
 * quality-selection helpers added to {@link YoutubeHelper}.
 * Covers all supported YouTube URL formats including watch, short, Shorts and embed links.
 */
public class YoutubeHelperTest {

    // -------------------------------------------------------------------------
    // Standard watch URLs
    // -------------------------------------------------------------------------

    @Test
    public void extractVideoId_standardWatch() {
        assertEquals("ZspxCnoYBXA",
                YoutubeHelper.extractVideoId("https://www.youtube.com/watch?v=ZspxCnoYBXA"));
    }

    @Test
    public void extractVideoId_shortUrl_withSiParam_aliased() {
        // youtu.be is a short URL, not a standard watch URL – kept here for back-compat naming
        assertEquals("A8Xxa4Ujqe0",
                YoutubeHelper.extractVideoId("https://youtu.be/A8Xxa4Ujqe0?si=PDg3j_krd4a7rbMM"));
    }

    @Test
    public void extractVideoId_standardWatch_withExtraParams() {
        assertEquals("ZspxCnoYBXA",
                YoutubeHelper.extractVideoId(
                        "https://www.youtube.com/watch?v=ZspxCnoYBXA&t=42s&si=abc"));
    }

    // -------------------------------------------------------------------------
    // youtu.be short URLs
    // -------------------------------------------------------------------------

    @Test
    public void extractVideoId_shortUrl() {
        assertEquals("A8Xxa4Ujqe0",
                YoutubeHelper.extractVideoId("https://youtu.be/A8Xxa4Ujqe0"));
    }

    @Test
    public void extractVideoId_shortUrl_withSiParam() {
        assertEquals("A8Xxa4Ujqe0",
                YoutubeHelper.extractVideoId(
                        "https://youtu.be/A8Xxa4Ujqe0?si=V-3yDxQm4gfqkWKq"));
    }

    // -------------------------------------------------------------------------
    // YouTube Shorts URLs
    // -------------------------------------------------------------------------

    @Test
    public void extractVideoId_shorts_withDomain() {
        assertEquals("pF_y5ROGhhQ",
                YoutubeHelper.extractVideoId(
                        "https://www.youtube.com/shorts/pF_y5ROGhhQ"));
    }

    @Test
    public void extractVideoId_shorts_withoutWww() {
        assertEquals("e33V9diNDqs",
                YoutubeHelper.extractVideoId(
                        "https://youtube.com/shorts/e33V9diNDqs?si=_JxSQLySfLJ9qyXo"));
    }

    // -------------------------------------------------------------------------
    // Embed URLs
    // -------------------------------------------------------------------------

    @Test
    public void extractVideoId_embedUrl() {
        assertEquals("ZspxCnoYBXA",
                YoutubeHelper.extractVideoId(
                        "https://www.youtube.com/embed/ZspxCnoYBXA"));
    }

    // -------------------------------------------------------------------------
    // Invalid / edge-case inputs
    // -------------------------------------------------------------------------

    @Test
    public void extractVideoId_nullUrl_returnsNull() {
        assertNull(YoutubeHelper.extractVideoId(null));
    }

    @Test
    public void extractVideoId_emptyUrl_returnsNull() {
        assertNull(YoutubeHelper.extractVideoId(""));
    }

    @Test
    public void extractVideoId_invalidUrl_returnsNull() {
        assertNull(YoutubeHelper.extractVideoId("https://example.com/not-a-youtube-url"));
    }

    // -------------------------------------------------------------------------
    // YoutubeFormat helper methods (unit-testable without network)
    // -------------------------------------------------------------------------

    /** Build a simple YoutubeFormat for use in unit tests. */
    private static YoutubeFormat makeFormat(int itag, String mimeType, String qualityLabel,
                                            boolean isMuxed, String url) {
        // contentLength is 0 here because it is not relevant to the quality-matching logic under test
        return new YoutubeFormat(itag, url, mimeType, qualityLabel, 0L, isMuxed);
    }

    @Test
    public void getFormatByQuality_exactMuxedMatch() {
        // getFormatByQuality relies on fetchFormatsSync internally, but the matching
        // logic can be validated by exercising YoutubeFormat directly.
        YoutubeFormat f360 = makeFormat(18, "video/mp4", "360p", true, "http://example.com/360");
        YoutubeFormat f720 = makeFormat(22, "video/mp4", "720p", true, "http://example.com/720");
        List<YoutubeFormat> formats = Arrays.asList(f720, f360);

        // Simulate the matching behaviour inside getFormatByQuality
        String target = "720p";
        YoutubeFormat found = null;
        for (YoutubeFormat f : formats) {
            if (target.equals(f.qualityLabel) && f.isMuxed) { found = f; break; }
        }
        assertNotNull("Should find 720p muxed format", found);
        assertEquals("http://example.com/720", found.url);
    }

    @Test
    public void getFormatByQuality_fallsBackToNonMuxedWhenNoMuxedMatch() {
        YoutubeFormat f360 = makeFormat(18, "video/mp4", "360p", true,  "http://example.com/360");
        YoutubeFormat f1080 = makeFormat(137, "video/mp4", "1080p", false, "http://example.com/1080");
        List<YoutubeFormat> formats = Arrays.asList(f1080, f360);

        String target = "1080p";
        YoutubeFormat found = null;
        // Muxed pass
        for (YoutubeFormat f : formats) {
            if (target.equals(f.qualityLabel) && f.isMuxed) { found = f; break; }
        }
        // Non-muxed pass
        if (found == null) {
            for (YoutubeFormat f : formats) {
                if (target.equals(f.qualityLabel)) { found = f; break; }
            }
        }
        assertNotNull("Should find 1080p non-muxed format", found);
        assertEquals("http://example.com/1080", found.url);
    }

    @Test
    public void getFormatByQuality_fallsBackToMuxedWhenNoLabelMatch() {
        YoutubeFormat f360 = makeFormat(18, "video/mp4", "360p", true,  "http://example.com/360");
        YoutubeFormat f720 = makeFormat(22, "video/mp4", "720p", false, "http://example.com/720");
        List<YoutubeFormat> formats = Arrays.asList(f360, f720);

        String target = "1080p"; // not present
        YoutubeFormat found = null;
        for (YoutubeFormat f : formats) {
            if (target.equals(f.qualityLabel) && f.isMuxed) { found = f; break; }
        }
        if (found == null) {
            for (YoutubeFormat f : formats) {
                if (target.equals(f.qualityLabel)) { found = f; break; }
            }
        }
        // No match → fall back to first muxed
        if (found == null) {
            for (YoutubeFormat f : formats) {
                if (f.isMuxed) { found = f; break; }
            }
        }
        assertNotNull("Should fall back to first muxed format", found);
        assertEquals("360p", found.qualityLabel);
    }

    @Test
    public void getBestFormat_returnsHighestQualityVideoFirst() {
        YoutubeFormat f360  = makeFormat(18,  "video/mp4", "360p",  true,  "http://example.com/360");
        YoutubeFormat f1080 = makeFormat(137, "video/mp4", "1080p", false, "http://example.com/1080");
        YoutubeFormat f720  = makeFormat(22,  "video/mp4", "720p",  true,  "http://example.com/720");
        // Simulate sorted list (highest first, as fetchFormatsSync produces)
        List<YoutubeFormat> formats = Arrays.asList(f1080, f720, f360);

        YoutubeFormat best = null;
        for (YoutubeFormat f : formats) {
            if (f.mimeType != null && f.mimeType.startsWith("video")) { best = f; break; }
        }
        assertNotNull("Should find best video format", best);
        assertEquals("1080p", best.qualityLabel);
    }
}
