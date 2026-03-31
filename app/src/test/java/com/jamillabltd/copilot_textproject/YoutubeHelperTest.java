package com.jamillabltd.copilot_textproject;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link YoutubeHelper#extractVideoId(String)}.
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
}
