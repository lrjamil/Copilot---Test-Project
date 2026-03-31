package com.jamillabltd.copilot_textproject;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.jamillabltd.copilot_textproject.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ExoPlayer player;
    private String selectedFormat = "MP4 (Video)";

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
                    appendLog(getString(R.string.msg_download_failed, error));
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setupFormatSpinner();
        setupPlayer();
        setupButtons();
        registerDownloadReceiver();
    }

    private void setupFormatSpinner() {
        List<String> formats = new ArrayList<>();
        String[] videoFormats = getResources().getStringArray(R.array.video_formats);
        String[] audioFormats = getResources().getStringArray(R.array.audio_formats);
        for (String f : videoFormats) formats.add(f);
        for (String f : audioFormats) formats.add(f);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, formats);
        binding.spinnerFormat.setAdapter(adapter);
        binding.spinnerFormat.setText(formats.get(0), false);
        selectedFormat = formats.get(0);

        binding.spinnerFormat.setOnItemClickListener((parent, view, position, id) -> {
            selectedFormat = formats.get(position);
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
    }

    private void onPlayClicked() {
        String url = getUrl();
        if (url == null) return;

        appendLog(getString(R.string.msg_loading));

        YoutubeHelper.extractStreamUrlAsync(url, new YoutubeHelper.StreamCallback() {
            @Override
            public void onSuccess(String streamUrl) {
                runOnUiThread(() -> {
                    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(streamUrl));
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    player.play();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> appendLog(getString(R.string.msg_no_stream) + " (" + message + ")"));
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

        String format = selectedFormat;

        if (audioOnly) {
            appendLog(getString(R.string.msg_audio_extract_started));
        } else {
            appendLog(getString(R.string.msg_download_started));
        }

        showProgress(0, "0%");

        Intent serviceIntent = new Intent(this, VideoDownloadService.class);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_URL, url);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_FORMAT, format);
        serviceIntent.putExtra(VideoDownloadService.EXTRA_AUDIO_ONLY, audioOnly);
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
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvProgressLabel.setVisibility(View.VISIBLE);
        binding.progressBar.setProgressCompat(progress, true);
        if (label != null) binding.tvProgressLabel.setText(label);
    }

    private void hideProgress() {
        binding.progressBar.setVisibility(View.GONE);
        binding.tvProgressLabel.setVisibility(View.GONE);
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