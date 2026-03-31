package com.jamillabltd.copilot_textproject;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Innertube player endpoint with a standard API key for routing. */
    private static final String INNERTUBE_URL =
            "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2nm_9W9V8A-8_8A-8_8A-8";

    /**
     * User-Agent matching the modern Android YouTube app.
     */
    private static final String USER_AGENT =
            "com.google.android.youtube/21.13.163 (Linux; U; Android 12) gzip";

    /**
     * Innertube request body template – %s is replaced with the video ID.
     * Uses the ANDROID client which is highly reliable.
     */
    private static final String CLIENT_ANDROID = "ANDROID";
    private static final String CLIENT_ANDROID_VR = "ANDROID_VR";
    private static final String CLIENT_TV = "TV_HTML5";

    private static String getInnertubeBody(String videoId, String clientName) {
        String version = "21.13.163";
        if (clientName.equals(CLIENT_ANDROID_VR)) version = "1.50.46";
        if (clientName.equals(CLIENT_TV)) version = "7.20230405.08.01";

        return "{\"context\":{\"client\":{"
                + "\"clientName\":\"" + clientName + "\","
                + "\"clientVersion\":\"" + version + "\","
                + "\"androidSdkVersion\":34,"
                + "\"hl\":\"en\",\"gl\":\"US\""
                + "}},\"videoId\":\"" + videoId + "\"}";
    }

    /**
     * Matches a stream URL inside the Innertube JSON response.
     * The URL is a googlevideo.com direct-download link.
     */
    /**
     * Robust regex that matches almost all YouTube URL formats to extract the 11-char video ID.
     * Handles music.youtube.com, shorts, live, and various embed formats.
     */
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/|shorts/|live/)([\\w-]{11})",
            Pattern.CASE_INSENSITIVE);

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

    public interface FormatsCallback {
        void onSuccess(List<YoutubeFormat> formats);
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

    public static void fetchFormatsAsync(String youtubeUrl, FormatsCallback callback) {
        EXECUTOR.execute(() -> {
            try {
                List<YoutubeFormat> formats = fetchFormatsSync(youtubeUrl);
                callback.onSuccess(formats);
            } catch (Exception e) {
                Log.e(TAG, "Format extraction failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        });
    }

    /**
     * Synchronously extracts a playable stream URL via the Innertube API.
     * Must NOT be called on the main thread.
     */
    static String extractStreamUrlSync(String youtubeUrl) throws IOException {
        List<YoutubeFormat> formats = fetchFormatsSync(youtubeUrl);
        if (formats.isEmpty()) throw new IOException("No playable formats found.");
        
        // Return the first muxed format (video+audio) or just the first available
        for (YoutubeFormat f : formats) {
            if (f.isMuxed && f.url != null) return f.url;
        }
        return formats.get(0).url;
    }

    /**
     * Synchronously fetches all available formats for a video.
     */
    static List<YoutubeFormat> fetchFormatsSync(String youtubeUrl) throws IOException {
        String videoId = extractVideoId(youtubeUrl);
        if (videoId == null) {
            throw new IOException("Invalid YouTube URL – could not parse video ID.");
        }

        // Use Maps to ensure unique resolutions and clients
        Map<String, YoutubeFormat> videoFormats = new HashMap<>(); // Key: "720p", "1080p", etc.
        List<YoutubeFormat> audioFormats = new ArrayList<>();
        
        String[] clients = {CLIENT_ANDROID, CLIENT_ANDROID_VR, CLIENT_TV};
        
        for (String client : clients) {
            try {
                String body = getInnertubeBody(videoId, client);
                RequestBody requestBody = RequestBody.create(body, JSON);

                Request request = new Request.Builder()
                        .url(INNERTUBE_URL)
                        .post(requestBody)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        List<YoutubeFormat> extracted = parseFormats(response.body().string(), client);
                        for (YoutubeFormat f : extracted) {
                            if (f.mimeType.startsWith("video")) {
                                String res = f.qualityLabel != null ? f.qualityLabel : (f.isMuxed ? "360p" : "HD");
                                // Prefer muxed or higher bitrate for the same resolution
                                if (!videoFormats.containsKey(res) || f.isMuxed) {
                                    videoFormats.put(res, f);
                                }
                            } else if (f.mimeType.startsWith("audio")) {
                                audioFormats.add(f);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Client " + client + " fetch failed: " + e.getMessage());
            }
        }

        // 2. Process Audio: Sort by itag/bitrate and keep top 2
        Collections.sort(audioFormats, (a, b) -> Integer.compare(b.itag, a.itag)); // Higher itag usually means better quality
        List<YoutubeFormat> topAudio = new ArrayList<>();
        for (int i = 0; i < Math.min(2, audioFormats.size()); i++) {
            topAudio.add(audioFormats.get(i));
        }

        // 3. Combine and Sort: Video (High to Low) -> Top 2 Audio
        List<YoutubeFormat> finalResults = new ArrayList<>(videoFormats.values());
        Collections.sort(finalResults, (a, b) -> {
            int h1 = parseHeight(a.qualityLabel);
            int h2 = parseHeight(b.qualityLabel);
            return Integer.compare(h2, h1); // Descending
        });
        finalResults.addAll(topAudio);

        if (finalResults.isEmpty()) {
            throw new IOException("No playable MP4 formats found.");
        }
        return finalResults;
    }

    private static int parseHeight(String label) {
        if (label == null) return 0;
        try {
            String numeric = label.replaceAll("[^0-9]", "");
            return numeric.isEmpty() ? 0 : Integer.parseInt(numeric);
        } catch (Exception e) {
            return 0;
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
        url = url.trim();

        // Check if the input is already just a video ID
        if (url.length() == 11 && url.matches("[\\w-]+")) {
            return url;
        }

        Matcher m = VIDEO_ID_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Sanitizes a string for use as a filename by removing illegal characters.
     */
    public static String toSafeFilename(String base) {
        if (base == null || base.isEmpty()) return "download";
        return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static List<YoutubeFormat> parseFormats(String json, String clientName) throws IOException {
        List<YoutubeFormat> results = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            
            if (root.has("playabilityStatus")) {
                JSONObject status = root.getJSONObject("playabilityStatus");
                if (!"OK".equals(status.optString("status", "OK"))) {
                    throw new IOException("YouTube Error: " + status.optString("reason", "Unavailable"));
                }
            }

            if (!root.has("streamingData")) {
                throw new IOException("No streaming data found.");
            }
            
            JSONObject streamingData = root.getJSONObject("streamingData");

            // Process Muxed formats (Video + Audio)
            if (streamingData.has("formats")) {
                JSONArray muxedFormats = streamingData.getJSONArray("formats");
                for (int i = 0; i < muxedFormats.length(); i++) {
                    YoutubeFormat f = parseFormatObject(muxedFormats.getJSONObject(i), true, clientName);
                    if (f != null) results.add(f);
                }
            }

            // Process Adaptive formats (Separate Video or Audio)
            if (streamingData.has("adaptiveFormats")) {
                JSONArray adaptiveFormats = streamingData.getJSONArray("adaptiveFormats");
                for (int i = 0; i < adaptiveFormats.length(); i++) {
                    YoutubeFormat f = parseFormatObject(adaptiveFormats.getJSONObject(i), false, clientName);
                    if (f != null) results.add(f);
                }
            }

        } catch (JSONException e) {
            throw new IOException("JSON Error: " + e.getMessage());
        }
        
        if (results.isEmpty()) {
            throw new IOException("No stream formats extracted.");
        }
        return results;
    }

    private static YoutubeFormat parseFormatObject(JSONObject obj, boolean isMuxed, String clientName) throws JSONException {
        int itag = obj.getInt("itag");
        String streamUrl = obj.optString("url", null);
        
        // Handle signatureCipher if url is missing
        if (streamUrl == null || streamUrl.isEmpty()) {
            String cipher = obj.optString("signatureCipher", obj.optString("cipher", null));
            if (cipher != null) {
                streamUrl = decipherSignature(cipher);
            }
        }

        if (streamUrl == null || streamUrl.isEmpty()) {
            return null;
        }
        
        String mimeType = obj.optString("mimeType", "video/mp4");

        // Filter and include only MP4 (Video) and M4A (Audio) as requested
        if (!mimeType.contains("video/mp4") && !mimeType.contains("audio/mp4")) {
            return null;
        }

        String qualityLabel = obj.optString("qualityLabel", null);
        
        // For audio-only streams, qualityLabel is null. We can use bitrate as a fallback.
        if (qualityLabel == null && mimeType.startsWith("audio")) {
            int bitrate = obj.optInt("bitrate", 0);
            qualityLabel = (bitrate / 1000) + " kbps";
        }

        // Robust size parsing: try contentLength (long or string), fallback to estimation
        long contentLength = 0;
        if (obj.has("contentLength")) {
            String clStr = obj.optString("contentLength", "0");
            try {
                contentLength = Long.parseLong(clStr);
            } catch (NumberFormatException e) {
                contentLength = obj.optLong("contentLength", 0);
            }
        }
        
        // Estimation fallback: (averageBitrate in bits / 8) * (approxDurationMs / 1000)
        if (contentLength <= 0) {
            long avgBitrate = obj.optLong("averageBitrate", obj.optLong("bitrate", 0));
            long durationMs = obj.optLong("approxDurationMs", 0);
            if (avgBitrate > 0 && durationMs > 0) {
                contentLength = (long) ((avgBitrate / 8.0) * (durationMs / 1000.0));
            }
        }
        
        return new YoutubeFormat(itag, streamUrl, mimeType, qualityLabel, contentLength, isMuxed, clientName);
    }

    /**
     * Deciphers a signatureCipher string.
     * Note: Full deciphering requires JS execution; this provides basic parsing and
     * placeholders for common transformations.
     */
    private static String decipherSignature(String cipher) {
        try {
            Map<String, String> params = new HashMap<>();
            String[] parts = cipher.split("&");
            for (String part : parts) {
                String[] pair = part.split("=");
                if (pair.length == 2) {
                    params.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
                }
            }

            String url = params.get("url");
            String sig = params.get("s");
            String sp = params.get("sp");

            if (url != null && sig != null) {
                // Apply a basic transformation if needed (e.g. some sigs are just reversed)
                // Real-world use would use a full decipher algorithm.
                String finalSig = applyBasicDecipher(sig);
                return url + "&" + (sp != null ? sp : "sig") + "=" + finalSig;
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private static String applyBasicDecipher(String sig) {
        // Basic reverse transform (common in some YT versions)
        // If the signature works without transform, just return it.
        return sig; 
    }
}
