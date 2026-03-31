package com.jamillabltd.copilot_textproject.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "download_history")
public class DownloadItem {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String title;
    public String videoId;
    public String filePath;
    public long timestamp;
    public long fileSize;
    public String format;
    public boolean isAudio;
    public String thumbnailUrl;

    public DownloadItem(String title, String videoId, String filePath, long timestamp, long fileSize, String format, boolean isAudio, String thumbnailUrl) {
        this.title = title;
        this.videoId = videoId;
        this.filePath = filePath;
        this.timestamp = timestamp;
        this.fileSize = fileSize;
        this.format = format;
        this.isAudio = isAudio;
        this.thumbnailUrl = thumbnailUrl;
    }
}
