package com.mermergi.dsh;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal file provider without androidx: serves the extracted Termux APK from our cache
 * directory to the system package installer. Only reads, only that one file.
 */
public class ApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File apk = TermuxInstaller.cachedApk(getContext());
        if (!apk.isFile() || apk.length() == 0) {
            throw new FileNotFoundException("bundled Termux APK not extracted yet");
        }
        try {
            return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            throw new FileNotFoundException(apk.getPath());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // The installer asks for DISPLAY_NAME / SIZE (OpenableColumns) before reading.
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        File apk = TermuxInstaller.cachedApk(getContext());
        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            String column = projection[i];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[i] = apk.getName();
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[i] = apk.isFile() ? apk.length() : 0L;
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
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
