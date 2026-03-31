package com.jamillabltd.copilot_textproject;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Data model representing a YouTube stream format.
 */
public class YoutubeFormat {
    public final int itag;
    public final String url;
    public final String mimeType;
    public final String qualityLabel;
    public final long contentLength;
    public final boolean isMuxed; // True if contains both audio and video
    public String clientName; // Source client (ANDROID, VR, TV)

    public YoutubeFormat(int itag, String url, String mimeType, String qualityLabel, long contentLength, boolean isMuxed) {
        this(itag, url, mimeType, qualityLabel, contentLength, isMuxed, "ANDROID");
    }

    public YoutubeFormat(int itag, String url, String mimeType, String qualityLabel, long contentLength, boolean isMuxed, String clientName) {
        this.itag = itag;
        this.url = url;
        this.mimeType = mimeType;
        this.qualityLabel = qualityLabel;
        this.contentLength = contentLength;
        this.isMuxed = isMuxed;
        this.clientName = clientName;
    }

    public String getExtension() {
        if (mimeType == null) return "mp4";
        if (mimeType.contains("video/mp4")) return "mp4";
        if (mimeType.contains("audio/mp4")) return "mp3";
        return "mp4";
    }

    public String getSizeLabel() {
        if (contentLength <= 0) return "Unknown size";
        if (contentLength < 1024) return contentLength + " B";
        if (contentLength < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", contentLength / 1024.0);
        if (contentLength < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", contentLength / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", contentLength / (1024.0 * 1014 * 1024));
    }

    public String getDisplayLabel() {
        String typeStr = isMuxed ? "Video" : 
                        (mimeType != null && mimeType.startsWith("audio") ? "Audio" : "VideoOnly");
        
        String ext = getExtension().toUpperCase();
        
        return String.format(Locale.getDefault(), "%s %s (%s) - %s",
                ext,
                (qualityLabel != null ? qualityLabel : (isMuxed ? "360p" : "HD")),
                typeStr,
                getSizeLabel());
    }

    @NonNull
    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
