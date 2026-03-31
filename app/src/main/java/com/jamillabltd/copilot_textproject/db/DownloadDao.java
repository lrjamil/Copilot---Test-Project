package com.jamillabltd.copilot_textproject.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface DownloadDao {
    @Insert
    void insert(DownloadItem item);

    @Delete
    void delete(DownloadItem item);

    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    LiveData<List<DownloadItem>> getAllDownloads();

    @Query("DELETE FROM download_history WHERE videoId = :videoId")
    void deleteByVideoId(String videoId);
}
