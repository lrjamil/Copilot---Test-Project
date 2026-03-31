package com.jamillabltd.copilot_textproject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.documentfile.provider.DocumentFile;
import android.net.Uri;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Foreground service that handles background video/audio downloads with
 * progress tracking and broadcast notifications to the UI.
 */
public class VideoDownloadService extends Service {

    private static final String TAG = "VideoDownloadService";
    private static final int NOTIFICATION_ID = 1001;

    // Intent extras
    public static final String EXTRA_URL        = "extra_url";
    public static final String EXTRA_FORMAT     = "extra_format";
    public static final String EXTRA_AUDIO_ONLY = "extra_audio_only";
    public static final String EXTRA_FOLDER_URI = "extra_folder_uri";
    public static final String EXTRA_DIRECT_URL = "extra_direct_url";

    // Broadcast actions
    public static final String ACTION_PROGRESS       = "com.jamillabltd.ACTION_PROGRESS";
    public static final String ACTION_COMPLETE       = "com.jamillabltd.ACTION_COMPLETE";
    public static final String ACTION_ERROR          = "com.jamillabltd.ACTION_ERROR";
    public static final String ACTION_CANCEL         = "com.jamillabltd.ACTION_CANCEL";
    public static final String EXTRA_PROGRESS        = "extra_progress";
    public static final String EXTRA_PROGRESS_LABEL  = "extra_progress_label";
    public static final String EXTRA_FILE_PATH       = "extra_file_path";
    public static final String EXTRA_ERROR           = "extra_error";

    private static final int PROGRESS_START_OFFSET = 5;
    private static final int PROGRESS_DOWNLOAD_RANGE = 90;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean isCancelled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            isCancelled = true;
            return START_NOT_STICKY;
        }

        String url       = intent.getStringExtra(EXTRA_URL);
        String format    = intent.getStringExtra(EXTRA_FORMAT);
        boolean audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false);
        String folderUri = intent.getStringExtra(EXTRA_FOLDER_URI);
        String directUrl = intent.getStringExtra(EXTRA_DIRECT_URL);

        isCancelled = false; // Reset for new task
        startForeground(NOTIFICATION_ID, buildNotification("Starting download…"));

        executor.execute(() -> download(url, format, audioOnly, folderUri, directUrl, startId));
        return START_NOT_STICKY;
    }

    // -------------------------------------------------------------------------
    // Download logic
    // -------------------------------------------------------------------------

    private void download(String youtubeUrl, String format, boolean audioOnly, String folderUriStr, String directUrl, int startId) {
        String fileName = "download.mp4"; // Default
        try {
            String streamUrl;
            if (directUrl != null) {
                streamUrl = directUrl;
            } else {
                broadcastProgress(0, "Extracting stream URL…");
                streamUrl = YoutubeHelper.extractStreamUrlSync(youtubeUrl);
            }

            String ext = resolveExtension(format, audioOnly);
            String videoId = YoutubeHelper.extractVideoId(youtubeUrl);
            fileName = YoutubeHelper.toSafeFilename(videoId != null ? videoId : "download") + "." + ext;
            String mimeType = audioOnly ? "audio/*" : "video/*";

            broadcastProgress(5, "Downloading…");

            DocumentFile targetFile = null;
            File fallbackFile = null;

            if (folderUriStr != null) {
                // Use Storage Access Framework (SAF)
                Uri folderUri = Uri.parse(folderUriStr);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(this, folderUri);
                if (pickedDir != null && pickedDir.canWrite()) {
                    DocumentFile file = pickedDir.createFile(mimeType, fileName);
                    targetFile = file;
                    if (file != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(file.getUri())) {
                            downloadToStream(streamUrl, out);
                            broadcastComplete(file.getUri().toString());
                            updateNotification("Download complete: " + (file.getName() != null ? file.getName() : fileName));
                            return;
                        }
                    }
                }
            }

            // Fallback to traditional File API
            File outputFile = resolveOutputFile(fileName);
            fallbackFile = outputFile;
            try (OutputStream out = new FileOutputStream(outputFile)) {
                downloadToStream(streamUrl, out);
                broadcastComplete(outputFile.getAbsolutePath());
                updateNotification("Download complete: " + fileName);
            }
            
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (isCancelled || "Cancelled".equalsIgnoreCase(errorMsg)) {
                // Cleanup partial file
                if (folderUriStr != null) {
                    DocumentFile pickedDir = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr));
                    if (pickedDir != null) {
                        DocumentFile partial = pickedDir.findFile(fileName);
                        if (partial != null) partial.delete();
                    }
                } else {
                    File outputFile = resolveOutputFile(fileName);
                    if (outputFile.exists()) outputFile.delete();
                }

                broadcastError("Cancelled");
                updateNotification("Download cancelled");
            } else {
                Log.e(TAG, "Download failed", e);
                broadcastError(errorMsg);
                updateNotification("Download failed");
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private void downloadToStream(String url, OutputStream out) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = YoutubeHelper.HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body");

            long totalBytes = body.contentLength();
            long downloadedBytes = 0;
            byte[] buffer = new byte[8192];
            int read;

            try (InputStream in = body.byteStream()) {
                while ((read = in.read(buffer)) != -1) {
                    if (isCancelled || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Cancelled");
                    }
                    out.write(buffer, 0, read);
                    downloadedBytes += read;
                    if (totalBytes > 0) {
                        int progress = (int) (PROGRESS_START_OFFSET
                                + ((downloadedBytes * PROGRESS_DOWNLOAD_RANGE) / totalBytes));
                        String label = formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);
                        broadcastProgress(progress, label);
                    }
                }
            }
        }
        broadcastProgress(100, "Done");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String resolveExtension(String format, boolean audioOnly) {
        if (format == null) return audioOnly ? "mp3" : "mp4";
        String lower = format.toLowerCase();
        if (lower.startsWith("mp4"))  return "mp4";
        if (lower.startsWith("webm")) return "webm";
        if (lower.startsWith("3gp"))  return "3gp";
        if (lower.startsWith("mp3"))  return "mp3";
        if (lower.startsWith("m4a"))  return "m4a";
        if (lower.startsWith("wav"))  return "wav";
        if (lower.startsWith("ogg"))  return "ogg";
        return audioOnly ? "mp3" : "mp4";
    }

    private File resolveOutputFile(String fileName) {
        // Try creating a dedicated folder in public Downloads first
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appFolder = new File(downloadsDir, DownloadConfig.getDefaultFolderName());
        
        if (!appFolder.exists()) {
            boolean created = appFolder.mkdirs();
            if (!created) {
                // If failed, use app-specific external files dir
                appFolder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            }
        }

        if (appFolder == null || !appFolder.canWrite()) {
            appFolder = getFilesDir();
        }
        
        return new File(appFolder, fileName);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private void broadcastProgress(int progress, String label) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.putExtra(EXTRA_PROGRESS, progress);
        i.putExtra(EXTRA_PROGRESS_LABEL, label);
        sendBroadcast(i);
    }

    private void broadcastComplete(String filePath) {
        Intent i = new Intent(ACTION_COMPLETE);
        i.putExtra(EXTRA_FILE_PATH, filePath);
        sendBroadcast(i);
    }

    private void broadcastError(String error) {
        Intent i = new Intent(ACTION_ERROR);
        i.putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    getString(R.string.channel_id),
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("YouTube downloader background service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, getString(R.string.channel_id))
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
