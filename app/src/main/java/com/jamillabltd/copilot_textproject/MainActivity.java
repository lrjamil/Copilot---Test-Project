package com.jamillabltd.copilot_textproject;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;

import com.jamillabltd.copilot_textproject.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ExoPlayer player;
    private String selectedFormat = "MP4 (Video)";
    private int selectedFormatIndex = -1;
    private DownloadConfig downloadConfig;
    private List<YoutubeFormat> availableFormats = new ArrayList<>();
    private String lastFetchedUrl = "";
    private boolean isFullscreen = false;

    private final String UA_ANDROID = "com.google.android.youtube/21.13.163 (Linux; U; Android 12) gzip";
    private final String UA_VR = "com.google.android.youtube.vr/1.50.46 (Linux; U; Android 12) gzip";
    private final String UA_TV = "Mozilla/5.0 (Web0S; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.122 Safari/537.36";

    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    downloadConfig.saveFolderUri(uri);
                    appendLog(getString(R.string.msg_folder_selected));
                }
            });

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    appendLog("Storage permission granted.");
                } else {
                    appendLog(getString(R.string.msg_permission_required));
                }
            });

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case VideoDownloadService.ACTION_PROGRESS: {
                    int progress = intent.getIntExtra(VideoDownloadService.EXTRA_PROGRESS, 0);
                    String label = intent.getStringExtra(VideoDownloadService.EXTRA_PROGRESS_LABEL);
                    showProgress(progress, label);
                    break;
                }
                case VideoDownloadService.ACTION_COMPLETE: {
                    String filePath = intent.getStringExtra(VideoDownloadService.EXTRA_FILE_PATH);
                    hideProgress();
                    appendLog(getString(R.string.msg_download_complete, filePath));
                    break;
                }
                case VideoDownloadService.ACTION_ERROR: {
                    String error = intent.getStringExtra(VideoDownloadService.EXTRA_ERROR);
                    hideProgress();
                    if ("Cancelled".equalsIgnoreCase(error)) {
                        appendLog("Download Cancelled.");
                    } else {
                        appendLog(getString(R.string.msg_download_failed, error));
                    }
                    break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            return insets;
        });

        setupFormatSpinner();
        setupPlayer();
        setupButtons();
        registerDownloadReceiver();
        downloadConfig = new DownloadConfig(this);

        // Restore last saved URL
        String lastUrl = getLastUrl();
        if (lastUrl != null && !lastUrl.isEmpty()) {
            binding.etUrl.setText(lastUrl);
        }
    }

    private void saveLastUrl(String url) {
        if (url == null || url.isEmpty()) return;
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .edit()
                .putString("LAST_URL", url)
                .apply();
    }

    private String getLastUrl() {
        return getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .getString("LAST_URL", "");
    }

    private void setupFormatSpinner() {
        if (availableFormats.isEmpty()) {
            List<String> defaultFormats = new ArrayList<>();
            String[] videoFormats = getResources().getStringArray(R.array.video_formats);
            String[] audioFormats = getResources().getStringArray(R.array.audio_formats);
            for (String f : videoFormats) defaultFormats.add(f);
            for (String f : audioFormats) defaultFormats.add(f);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, defaultFormats);
            binding.spinnerFormat.setAdapter(adapter);
            binding.spinnerFormat.setText(defaultFormats.get(0), false);
            selectedFormat = defaultFormats.get(0);
        } else {
            ArrayAdapter<YoutubeFormat> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, availableFormats);
            binding.spinnerFormat.setAdapter(adapter);

            // Prefer highest-quality muxed format; fall back to highest-quality video; then first item.
            int recommendedIndex = -1;
            // 1. Best muxed (formats are sorted highest-to-lowest, first muxed = best muxed)
            for (int i = 0; i < availableFormats.size(); i++) {
                if (availableFormats.get(i).isMuxed) {
                    recommendedIndex = i;
                    break;
                }
            }
            // 2. If no muxed found, use the first video format
            if (recommendedIndex == -1) {
                for (int i = 0; i < availableFormats.size(); i++) {
                    YoutubeFormat f = availableFormats.get(i);
                    if (f.mimeType != null && f.mimeType.startsWith("video")) {
                        recommendedIndex = i;
                        break;
                    }
                }
            }

            int finalIndex = Math.max(0, recommendedIndex);
            binding.spinnerFormat.setText(availableFormats.get(finalIndex).getDisplayLabel(), false);
            selectedFormat = availableFormats.get(finalIndex).getDisplayLabel();
            selectedFormatIndex = finalIndex;
        }

        binding.spinnerFormat.setOnItemClickListener((parent, view, position, id) -> {
            if (availableFormats.isEmpty()) {
                selectedFormat = (String) parent.getItemAtPosition(position);
                selectedFormatIndex = -1;
            } else {
                selectedFormatIndex = position;
                selectedFormat = availableFormats.get(position).getDisplayLabel();
                // If already playing or prepared, switch quality immediately
                if (player.getPlaybackState() != Player.STATE_IDLE) {
                    onPlayClicked();
                }
            }
            appendLog("Format selected: " + selectedFormat);
        });
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        binding.playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    appendLog(getString(R.string.msg_playback_started));
                } else if (state == Player.STATE_ENDED) {
                    appendLog("Playback ended.");
                }
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                appendLog("Player error: " + error.getMessage());
            }
        });
    }

    private void setupButtons() {
        binding.btnPlay.setOnClickListener(v -> onPlayClicked());
        binding.btnStop.setOnClickListener(v -> onStopClicked());
        binding.btnDownloadVideo.setOnClickListener(v -> onDownloadClicked(false));
        binding.btnDownloadAudio.setOnClickListener(v -> onDownloadClicked(true));
        
        binding.btnFullscreen.setOnClickListener(v -> toggleFullscreen());
        binding.btnPip.setOnClickListener(v -> enterPipMode());

        binding.btnCancelDownload.setOnClickListener(v -> {
            Intent cancelIntent = new Intent(this, VideoDownloadService.class);
            cancelIntent.setAction(VideoDownloadService.ACTION_CANCEL);
            startService(cancelIntent);
            appendLog("Cancelling download…");
        });
    }

    private void onPlayClicked() {
        String url = getUrl();
        if (url == null) return;
        saveLastUrl(url); // Save URL on play

        if (!url.equals(lastFetchedUrl)) {
            fetchFormatsAndExecute(url, this::onPlayClicked);
            return;
        }

        appendLog(getString(R.string.msg_loading));
        
        YoutubeFormat selected = null;
        if (selectedFormatIndex != -1 && selectedFormatIndex < availableFormats.size()) {
            selected = availableFormats.get(selectedFormatIndex);
        } else if (!availableFormats.isEmpty()) {
            for (YoutubeFormat f : availableFormats) {
                if (f.getDisplayLabel().equals(selectedFormat)) {
                    selected = f;
                    break;
                }
            }
        }

        if (selected == null) {
            // Fallback
            YoutubeHelper.extractStreamUrlAsync(url, new YoutubeHelper.StreamCallback() {
                @Override
                public void onSuccess(String url) {
                    startPlayback(url);
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> appendLog(getString(R.string.msg_no_stream) + " (" + message + ")"));
                }
            });
        } else {
            // Check if it's a VideoOnly format for Smart Merging
            if (!selected.isMuxed && selected.mimeType.startsWith("video")) {
                // Find best audio (prioritize itag 140 or highest kbps)
                YoutubeFormat bestAudio = null;
                for (YoutubeFormat f : availableFormats) {
                    if (f.mimeType.startsWith("audio")) {
                        // itag 140 is usually very stable m4a
                        if (f.itag == 140) {
                            bestAudio = f;
                            break;
                        }
                        if (bestAudio == null || f.itag > bestAudio.itag) {
                            bestAudio = f;
                        }
                    }
                }
                if (bestAudio != null) {
                    startPlayback(selected.url, bestAudio.url);
                } else {
                    startPlayback(selected.url);
                }
            } else {
                startPlayback(selected.url);
            }
        }
    }

    private void startPlayback(String streamUrl) {
        startPlayback(streamUrl, null);
    }

    private void startPlayback(String videoUrl, String audioUrl) {
        runOnUiThread(() -> {
            boolean wasPlaying = player.isPlaying();
            long currentPos = player.getCurrentPosition();

            // Select matching User-Agent for this stream's source client
            String playerUA = UA_ANDROID;
            if (selectedFormatIndex != -1 && selectedFormatIndex < availableFormats.size()) {
                YoutubeFormat f = availableFormats.get(selectedFormatIndex);
                if ("ANDROID_VR".equals(f.clientName)) playerUA = UA_VR;
                else if ("TV_HTML5".equals(f.clientName)) playerUA = UA_TV;
            }

            // Use HttpDataSource.Factory with synchronized User-Agent
            HttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(playerUA)
                    .setAllowCrossProtocolRedirects(true);
            
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://www.youtube.com/");
            headers.put("Origin", "https://www.youtube.com/");
            headers.put("Accept-Language", "en-US,en;q=0.9");
            headers.put("Connection", "keep-alive");
            httpFactory.setDefaultRequestProperties(headers);
            
            DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
            
            MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)));

            if (audioUrl != null) {
                MediaSource audioSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)));
                MergingMediaSource mergingSource = new MergingMediaSource(videoSource, audioSource);
                player.setMediaSource(mergingSource);
            } else {
                player.setMediaSource(videoSource);
            }

            player.prepare();
            if (wasPlaying || currentPos > 0) {
                player.seekTo(currentPos);
                player.play();
            } else {
                player.play();
            }
            appendLog("Playing quality: " + selectedFormat + (audioUrl != null ? " (Smart Merging)" : ""));
        });
    }

    private void toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        // Adjust player container height
        ViewGroup.LayoutParams lp = binding.playerContainer.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        binding.playerContainer.setLayoutParams(lp);
        
        // Hide other items correctly
        binding.mainScrollView.setVisibility(View.GONE);
        binding.headerContainer.setVisibility(View.GONE); // Hide header
        
        isFullscreen = true;
    }

    private void exitFullscreen() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        // Reset player container height (220dp approx)
        ViewGroup.LayoutParams lp = binding.playerContainer.getLayoutParams();
        lp.height = (int) (220 * getResources().getDisplayMetrics().density);
        binding.playerContainer.setLayoutParams(lp);
        
        binding.mainScrollView.setVisibility(View.VISIBLE);
        binding.headerContainer.setVisibility(View.VISIBLE); // Show header
        
        isFullscreen = false;
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Rational ratio = new Rational(16, 9);
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio)
                    .build();
            enterPictureInPictureMode(params);
        } else {
            appendLog("PiP mode is not supported on this Android version.");
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        updateHeaderForOverlay(isInPictureInPictureMode);
        
        if (isInPictureInPictureMode) {
            // Hide other UI
            binding.mainScrollView.setVisibility(View.GONE);
            binding.btnFullscreen.setVisibility(View.GONE);
            binding.btnPip.setVisibility(View.GONE);
            binding.playerView.setUseController(false);
        } else {
            // Restore UI
            binding.mainScrollView.setVisibility(View.VISIBLE);
            binding.btnFullscreen.setVisibility(View.VISIBLE);
            binding.btnPip.setVisibility(View.VISIBLE);
            binding.playerView.setUseController(true);
        }
    }

    private void updateHeaderForOverlay(boolean isInOverlay) {
        if (isInOverlay) {
            // "Cikon" (Thin) & Small style for Overlay
            binding.headerTitle.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, (int) (8 * getResources().getDisplayMetrics().density));
            binding.headerTitle.setTextSize(14f);
            binding.headerTitle.setTypeface(android.graphics.Typeface.DEFAULT); // Not Bold
        } else {
            // Professional Bold & Large style for Normal
            binding.headerTitle.setPadding(0, (int) (40 * getResources().getDisplayMetrics().density), 0, (int) (12 * getResources().getDisplayMetrics().density));
            binding.headerTitle.setTextSize(22f);
            binding.headerTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
    }

    private void fetchFormatsAndExecute(String url, Runnable nextAction) {
        appendLog("Fetching available formats…");
        showProgress(10, "Fetching…");
        YoutubeHelper.fetchFormatsAsync(url, new YoutubeHelper.FormatsCallback() {
            @Override
            public void onSuccess(List<YoutubeFormat> formats) {
                runOnUiThread(() -> {
                    hideProgress();
                    
                    // Filter formats based on user preference
                    List<YoutubeFormat> filtered = new ArrayList<>();
                    
                    // Group by extension to pick best for "Others"
                    java.util.Map<String, List<YoutubeFormat>> groups = new java.util.HashMap<>();
                    for (YoutubeFormat f : formats) {
                        String ext = f.getExtension();
                        if (!groups.containsKey(ext)) groups.put(ext, new ArrayList<>());
                        groups.get(ext).add(f);
                    }
                    
                    for (String ext : groups.keySet()) {
                        List<YoutubeFormat> groupFormats = groups.get(ext);
                        // Sort by itag/bitrate (descending)
                        groupFormats.sort((a, b) -> b.itag - a.itag); 
                        
                        if (ext.equalsIgnoreCase("mp4")) {
                            // Keep MP4s (Both Muxed and Adaptive Video)
                            for (YoutubeFormat f : groupFormats) {
                                if (f.isMuxed || f.mimeType.startsWith("video")) {
                                    filtered.add(f);
                                }
                            }
                        } else if (ext.equalsIgnoreCase("mp3") || ext.equalsIgnoreCase("m4a")) {
                            // Keep top 2 qualities for audio
                            for (int i = 0; i < Math.min(2, groupFormats.size()); i++) {
                                filtered.add(groupFormats.get(i));
                            }
                        } else {
                            // Others: Keep only 1 (best)
                            if (!groupFormats.isEmpty()) filtered.add(groupFormats.get(0));
                        }
                    }

                    availableFormats.clear();
                    availableFormats.addAll(filtered);
                    lastFetchedUrl = url;
                    setupFormatSpinner();
                    appendLog("Found " + filtered.size() + " reliable formats.");
                    if (nextAction != null) nextAction.run();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    hideProgress();
                    appendLog("Failed to fetch formats: " + message);
                });
            }
        });
    }

    private void onStopClicked() {
        player.stop();
        player.clearMediaItems();
        appendLog(getString(R.string.msg_playback_stopped));
    }

    private void onDownloadClicked(boolean audioOnly) {
        String url = getUrl();
        if (url == null) return;

        if (!checkStoragePermission()) return;

        showDownloadConfirmationDialog(audioOnly);
    }

    private void showDownloadConfirmationDialog(boolean audioOnly) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom_download, null);
        TextView tvPath = dialogView.findViewById(R.id.tv_dialog_path);
        com.google.android.material.button.MaterialButton btnConfirm = dialogView.findViewById(R.id.btn_dialog_confirm);
        com.google.android.material.button.MaterialButton btnChange = dialogView.findViewById(R.id.btn_dialog_change);
        com.google.android.material.button.MaterialButton btnCancel = dialogView.findViewById(R.id.btn_dialog_cancel);

        tvPath.setText(downloadConfig.getDisplayPath());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog_custom);
        }

        btnConfirm.setOnClickListener(v -> {
            startDownloadService(audioOnly);
            dialog.dismiss();
        });
        btnChange.setOnClickListener(v -> {
            folderPickerLauncher.launch(null);
            dialog.dismiss();
        });
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void startDownloadService(boolean audioOnly) {
        String url = getUrl();
        if (url == null) return;

        if (!url.equals(lastFetchedUrl)) {
            fetchFormatsAndExecute(url, () -> startDownloadService(audioOnly));
            return;
        }

        String format = selectedFormat;
        String folderUri = downloadConfig.getFolderUriString();

        // Resolve the selected YoutubeFormat (by index first, then by label)
        YoutubeFormat selectedYtFormat = null;
        if (selectedFormatIndex != -1 && selectedFormatIndex < availableFormats.size()) {
            selectedYtFormat = availableFormats.get(selectedFormatIndex);
        } else {
            for (YoutubeFormat f : availableFormats) {
                if (f.getDisplayLabel().equals(format)) {
                    selectedYtFormat = f;
                    break;
                }
            }
        }

        // Derive direct stream URL and quality label from the resolved format
        String selectedStreamUrl = selectedYtFormat != null ? selectedYtFormat.url : null;
        String qualityLabel      = selectedYtFormat != null ? selectedYtFormat.qualityLabel : null;

        if (audioOnly) {
            appendLog(getString(R.string.msg_audio_extract_started));
        } else {
            String qualityInfo = (qualityLabel != null) ? qualityLabel : null;
            if (qualityInfo != null) {
                appendLog(getString(R.string.msg_download_started_quality, qualityInfo));
            } else {
                appendLog(getString(R.string.msg_download_started));
            }
        }

        showProgress(0, "0%");

        Intent serviceIntent = new Intent(this, VideoDownloadService.class);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_URL, url);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_FORMAT, format);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_AUDIO_ONLY, audioOnly);
        if (selectedStreamUrl != null) {
            serviceIntent.putExtra(VideoDownloadService.EXTRA_DIRECT_URL, selectedStreamUrl);
        }
        if (qualityLabel != null) {
            serviceIntent.putExtra(VideoDownloadService.EXTRA_QUALITY_LABEL, qualityLabel);
        }
        if (folderUri != null) {
            serviceIntent.putExtra(VideoDownloadService.EXTRA_FOLDER_URI, folderUri);
        }
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private String getUrl() {
        String url = binding.etUrl.getText() != null
                ? binding.etUrl.getText().toString().trim()
                : "";
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, R.string.msg_enter_url, Toast.LENGTH_SHORT).show();
            return null;
        }
        return url;
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return false;
    }

    private void showProgress(int progress, String label) {
        binding.progressContainer.setVisibility(View.VISIBLE);
        binding.progressBar.setProgressCompat(progress, true);
        if (label != null) binding.tvProgressLabel.setText(label);
    }

    private void hideProgress() {
        binding.progressContainer.setVisibility(View.GONE);
    }

    private void appendLog(String message) {
        String current = binding.tvStatus.getText().toString();
        String updated = current.isEmpty() ? message : current + "\n" + message;
        binding.tvStatus.setText(updated);
    }

    private void registerDownloadReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(VideoDownloadService.ACTION_PROGRESS);
        filter.addAction(VideoDownloadService.ACTION_COMPLETE);
        filter.addAction(VideoDownloadService.ACTION_ERROR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        try {
            unregisterReceiver(downloadReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered
        }
    }
}