package com.foldshot.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Lightweight ContentProvider that exposes cropped screenshot PNGs from cacheDir/clipboard/
 * to the Android system ClipboardManager and Samsung Quick Share sheet with strict canonical path checks.
 */
public class ClipboardFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.foldshot.app.fileprovider";

    public static Uri getUriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("clipboard")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveSafeClipboardFile(Uri uri) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null || uri == null) {
            throw new FileNotFoundException("Invalid request");
        }
        String fileName = uri.getLastPathSegment();
        if (fileName == null || fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new FileNotFoundException("Invalid file name");
        }
        File clipDir = new File(ctx.getCacheDir(), "clipboard");
        File target = new File(clipDir, fileName);
        try {
            String baseCanonical = clipDir.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical + File.separator)) {
                throw new SecurityException("Blocked path traversal outside clipboard directory");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Unable to resolve canonical path");
        }
        return target;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File target = resolveSafeClipboardFile(uri);
        if (!target.exists()) {
            throw new FileNotFoundException("File not found: " + target.getName());
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        try {
            File target = resolveSafeClipboardFile(uri);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = target.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = target.exists() ? target.length() : 0L;
                }
            }
            cursor.addRow(row);
        } catch (Exception ignored) {
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
