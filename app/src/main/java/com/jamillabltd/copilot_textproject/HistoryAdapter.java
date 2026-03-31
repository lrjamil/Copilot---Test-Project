package com.jamillabltd.copilot_textproject;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.jamillabltd.copilot_textproject.databinding.ItemDownloadHistoryBinding;
import com.jamillabltd.copilot_textproject.db.DownloadItem;
import android.text.format.DateUtils;

public class HistoryAdapter extends ListAdapter<DownloadItem, HistoryAdapter.ViewHolder> {

    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onDelete(DownloadItem item);
        void onItemClick(DownloadItem item);
    }

    public HistoryAdapter(OnItemClickListener listener) {
        super(new DiffUtil.ItemCallback<DownloadItem>() {
            @Override
            public boolean areItemsTheSame(@NonNull DownloadItem oldItem, @NonNull DownloadItem newItem) {
                return oldItem.id == newItem.id;
            }

            @Override
            public boolean areContentsTheSame(@NonNull DownloadItem oldItem, @NonNull DownloadItem newItem) {
                return oldItem.title.equals(newItem.title) && oldItem.filePath.equals(newItem.filePath);
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDownloadHistoryBinding binding = ItemDownloadHistoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemDownloadHistoryBinding binding;

        ViewHolder(ItemDownloadHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(DownloadItem item) {
            binding.tvTitle.setText(item.title);
            String time = DateUtils.getRelativeTimeSpanString(item.timestamp).toString();
            String size = formatBytes(item.fileSize);
            binding.tvInfo.setText(item.format.toUpperCase() + " • " + size + " • " + time);

            binding.btnDelete.setOnClickListener(v -> listener.onDelete(item));
            binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));

            // Load real thumbnail using Glide
            Glide.with(binding.ivThumb.getContext())
                    .load(item.thumbnailUrl)
                    .placeholder(R.drawable.ic_folder_download)
                    .error(android.R.drawable.ic_dialog_alert)
                    .centerCrop()
                    .into(binding.ivThumb);

            binding.ivTypeIcon.setImageResource(item.isAudio ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_media_play);
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
}
