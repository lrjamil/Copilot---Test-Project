package com.jamillabltd.copilot_textproject;

import android.util.Log;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Helper class for extracting playable stream URLs from YouTube video pages.
 *
 * <p>This implementation uses YouTube's public web page to locate the video stream URL.
 * For production use, integrate a maintained extractor library (e.g., NewPipe Extractor
 * or the youtubedl-android yt-dlp wrapper) to handle YouTube's evolving page structure.
 */
public final class YoutubeHelper {

    private static final String TAG = "YoutubeHelper";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    /** Pattern to find a direct MP4 stream URL inside YouTube's page source. */
    private static final Pattern STREAM_URL_PATTERN =
            Pattern.compile("\"url\":\"(https://[^\"]+\\.googlevideo\\.com[^\"]+)\"");

    private YoutubeHelper() {}

    /**
     * Callback interface delivered on a background thread (except when called with
     * {@link #extractStreamUrlAsync} which posts back to the caller's thread via
     * {@link android.os.Handler} where needed).
     */
    public interface StreamCallback {
        void onSuccess(String streamUrl);
        void onError(String message);
    }

    /**
     * Asynchronously extracts a playable stream URL for the given YouTube video URL.
     * The callback methods are invoked on a background thread; post to the main thread
     * inside the implementation (e.g. {@code Activity.runOnUiThread}) as required.
     *
     * @param youtubeUrl full YouTube video URL (e.g. https://www.youtube.com/watch?v=...)
     * @param callback   result callback
     */
    public static void extractStreamUrlAsync(String youtubeUrl, StreamCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                String streamUrl = extractStreamUrlSync(youtubeUrl);
                callback.onSuccess(streamUrl);
            } catch (Exception e) {
                Log.e(TAG, "Stream extraction failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    /**
     * Synchronously extracts a playable stream URL.
     * Must NOT be called on the main thread.
     */
    static String extractStreamUrlSync(String youtubeUrl) throws IOException {
        String videoId = extractVideoId(youtubeUrl);
        if (videoId == null) {
            throw new IOException("Invalid YouTube URL – could not parse video ID.");
        }

        String pageUrl = "https://www.youtube.com/watch?v=" + videoId + "&hl=en";
        Request request = new Request.Builder()
                .url(pageUrl)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " fetching YouTube page.");
            }
            String body = response.body().string();
            return parseStreamUrl(body);
        }
    }

    /**
     * Extracts the 11-character YouTube video ID from a variety of URL formats.
     */
    public static String extractVideoId(String url) {
        if (url == null || url.isEmpty()) return null;

        // Standard watch URL: ?v=VIDEO_ID
        Pattern watchPattern = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
        Matcher m = watchPattern.matcher(url);
        if (m.find()) return m.group(1);

        // Short URL: youtu.be/VIDEO_ID
        Pattern shortPattern = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})");
        m = shortPattern.matcher(url);
        if (m.find()) return m.group(1);

        // Embed URL: /embed/VIDEO_ID
        Pattern embedPattern = Pattern.compile("/embed/([a-zA-Z0-9_-]{11})");
        m = embedPattern.matcher(url);
        if (m.find()) return m.group(1);

        return null;
    }

    private static String parseStreamUrl(String pageBody) throws IOException {
        Matcher m = STREAM_URL_PATTERN.matcher(pageBody);
        if (m.find()) {
            String raw = m.group(1);
            try {
                return URLDecoder.decode(raw.replace("\\u0026", "&"), StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return raw;
            }
        }
        throw new IOException(
                "Stream URL not found in page. YouTube may have changed its structure.");
    }
}
