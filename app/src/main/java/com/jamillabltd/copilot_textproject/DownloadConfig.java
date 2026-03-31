package com.jamillabltd.copilot_textproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;

/**
 * Helper class to manage download configuration, including selected folder URIs.
 */
public class DownloadConfig {

    private static final String PREF_NAME = "download_prefs";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String DEFAULT_FOLDER_NAME = "CopilotDownloads";

    private final SharedPreferences prefs;
    private final Context context;

    public DownloadConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Re-saves the folder URI and takes persistable permission if possible.
     */
    public void saveFolderUri(Uri uri) {
        if (uri == null) return;
        
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // Permission might not be persistable for all URIs
        }

        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply();
    }

    /**
     * Gets the stored folder URI as a string, or null if none.
     */
    public String getFolderUriString() {
        return prefs.getString(KEY_FOLDER_URI, null);
    }

    /**
     * Gets a user-friendly display name for the current download location.
     */
    public String getDisplayPath() {
        String uriStr = getFolderUriString();
        if (uriStr == null) {
            // Default path on modern Android is public Downloads/CopilotDownloads
            return "Downloads/" + DEFAULT_FOLDER_NAME;
        }

        Uri uri = Uri.parse(uriStr);
        DocumentFile documentFile = DocumentFile.fromTreeUri(context, uri);
        if (documentFile != null && documentFile.exists()) {
            return documentFile.getName();
        }
        
        return "Custom Location";
    }

    /**
     * Gets the default folder name for creating a folder in public directory.
     */
    public static String getDefaultFolderName() {
        return DEFAULT_FOLDER_NAME;
    }
}
