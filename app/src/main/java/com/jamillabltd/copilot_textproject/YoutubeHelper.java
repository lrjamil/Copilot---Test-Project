package com.jamillabltd.copilot_textproject;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Helper class for extracting playable stream URLs from YouTube videos.
 *
 * <p>Uses YouTube's Innertube API (the same internal API used by YouTube's own
 * Android client) to obtain stream manifests. Supports all common YouTube URL
 * formats including regular watch links, short youtu.be links, and Shorts.
 */
public final class YoutubeHelper {

    private static final String TAG = "YoutubeHelper";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Innertube player endpoint (no key needed for ANDROID_TESTSUITE client). */
    private static final String INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player";

    /**
     * User-Agent that matches the ANDROID_TESTSUITE client config below.
     * Version 17.36.4 is a known-good value; update if Innertube API requests
     * start receiving HTTP 403 or empty stream lists.
     */
    private static final String USER_AGENT =
            "com.google.android.youtube/17.36.4 (Linux; U; Android 12) gzip";

    /**
     * Innertube request body template – %s is replaced with the video ID.
     * Uses the lightweight ANDROID_TESTSUITE client which returns plain stream
     * URLs without cipher/signature scrambling.
     */
    private static final String INNERTUBE_BODY_TEMPLATE =
            "{\"context\":{\"client\":{"
            + "\"clientName\":\"ANDROID_TESTSUITE\","
            + "\"clientVersion\":\"1.9\","
            + "\"androidSdkVersion\":30,"
            + "\"hl\":\"en\",\"gl\":\"US\""
            + "}},\"videoId\":\"%s\"}";

    /**
     * Matches a stream URL inside the Innertube JSON response.
     * The URL is a googlevideo.com direct-download link.
     */
    private static final Pattern STREAM_URL_PATTERN =
            Pattern.compile("\"url\":\"(https://[^\"]*\\.googlevideo\\.com[^\"]*)\"");

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
     * Synchronously extracts a playable stream URL via the Innertube API.
     * Must NOT be called on the main thread.
     */
    static String extractStreamUrlSync(String youtubeUrl) throws IOException {
        String videoId = extractVideoId(youtubeUrl);
        if (videoId == null) {
            throw new IOException("Invalid YouTube URL – could not parse video ID.");
        }

        String body = String.format(INNERTUBE_BODY_TEMPLATE, videoId);
        RequestBody requestBody = RequestBody.create(body, JSON);

        Request request = new Request.Builder()
                .url(INNERTUBE_URL)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " from Innertube API.");
            }
            String responseBody = response.body().string();
            return parseStreamUrl(responseBody);
        }
    }

    /**
     * Extracts the 11-character YouTube video ID from a variety of URL formats:
     * <ul>
     *   <li>{@code https://www.youtube.com/watch?v=VIDEO_ID}</li>
     *   <li>{@code https://youtu.be/VIDEO_ID}</li>
     *   <li>{@code https://youtube.com/shorts/VIDEO_ID}</li>
     *   <li>{@code https://www.youtube.com/embed/VIDEO_ID}</li>
     * </ul>
     * Query parameters such as {@code ?si=} and {@code ?t=} are ignored.
     */
    public static String extractVideoId(String url) {
        if (url == null || url.isEmpty()) return null;

        // Standard watch URL: ?v=VIDEO_ID (also handles extra params like &si=)
        Pattern watchPattern = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})");
        Matcher m = watchPattern.matcher(url);
        if (m.find()) return m.group(1);

        // Short URL: youtu.be/VIDEO_ID
        Pattern shortPattern = Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})");
        m = shortPattern.matcher(url);
        if (m.find()) return m.group(1);

        // Shorts URL: /shorts/VIDEO_ID
        Pattern shortsPattern = Pattern.compile("/shorts/([a-zA-Z0-9_-]{11})");
        m = shortsPattern.matcher(url);
        if (m.find()) return m.group(1);

        // Embed URL: /embed/VIDEO_ID
        Pattern embedPattern = Pattern.compile("/embed/([a-zA-Z0-9_-]{11})");
        m = embedPattern.matcher(url);
        if (m.find()) return m.group(1);

        return null;
    }

    private static String parseStreamUrl(String json) throws IOException {
        Matcher m = STREAM_URL_PATTERN.matcher(json);
        if (m.find()) {
            // Unescape JSON unicode sequences and forward-slashes
            return m.group(1)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");
        }
        throw new IOException("Could not extract stream URL. Check the URL and try again.");
    }
}
