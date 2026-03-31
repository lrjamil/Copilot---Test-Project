package com.jamillabltd.copilot_textproject;

import android.os.Bundle;
import android.view.View;
import com.google.android.material.color.DynamicColors;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.jamillabltd.copilot_textproject.databinding.ActivityHistoryBinding;
import com.jamillabltd.copilot_textproject.db.AppDatabase;
import com.jamillabltd.copilot_textproject.db.DownloadItem;
import java.util.List;

public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.OnItemClickListener {

    private ActivityHistoryBinding binding;
    private HistoryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        setupRecyclerView();
        observeDownloads();
    }

    private void setupRecyclerView() {
        adapter = new HistoryAdapter(this);
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this));
        binding.rvHistory.setAdapter(adapter);
    }

    private void observeDownloads() {
        AppDatabase.getDatabase(this).downloadDao().getAllDownloads().observe(this, downloads -> {
            if (downloads == null || downloads.isEmpty()) {
                binding.emptyState.setVisibility(View.VISIBLE);
                binding.rvHistory.setVisibility(View.GONE);
            } else {
                binding.emptyState.setVisibility(View.GONE);
                binding.rvHistory.setVisibility(View.VISIBLE);
                adapter.submitList(downloads);
            }
        });
    }

    @Override
    public void onDelete(DownloadItem item) {
        new Thread(() -> {
            AppDatabase.getDatabase(this).downloadDao().delete(item);
        }).start();
    }

    @Override
    public void onItemClick(DownloadItem item) {
        // Future implementation: Open file or play video
    }
}
