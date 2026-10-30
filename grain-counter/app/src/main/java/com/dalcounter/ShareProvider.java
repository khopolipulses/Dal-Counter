package com.dalcounter;

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
import java.util.List;
import java.util.regex.Pattern;

/**
 * Serves the app's own temporary pictures (a picture being shared, a photo just taken) to other apps, read-only and
 * only through a one-off permission grant. Not exported. Paths are locked down: only two folders in the app cache
 * and plain file names are accepted, so a crafted Uri ("../", encoded slashes, other folders) can never reach any
 * other file.
 */
public final class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "com.dalcounter.files";
    static final String SHARE = "share";
    static final String CAMERA = "camera";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,80}\\.(png|jpg)");

    static File dir(Context c, String kind) {
        File d = new File(c.getCacheDir(), kind);
        d.mkdirs();
        return d;
    }

    static Uri uri(String kind, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(kind).appendPath(name).build();
    }

    /** True when the name is safe to use as a file name in one of our folders. */
    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Deletes files older than maxAgeMs in one of the folders (temporary pictures do not pile up). */
    static void prune(Context c, String kind, long maxAgeMs) {
        File[] files = dir(c, kind).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) if (now - f.lastModified() > maxAgeMs) f.delete();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || seg.size() != 2) throw new FileNotFoundException("bad uri");
        String kind = seg.get(0), name = seg.get(1);
        if (!(SHARE.equals(kind) || CAMERA.equals(kind)) || !validName(name)) throw new FileNotFoundException("bad uri");
        Context c = getContext();
        if (c == null) throw new FileNotFoundException("no context");
        try {
            File base = dir(c, kind).getCanonicalFile();
            File f = new File(base, name).getCanonicalFile();
            if (!base.equals(f.getParentFile()) || !f.isFile()) throw new FileNotFoundException("not found");
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException("not found");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        return p.endsWith(".png") ? "image/png" : p.endsWith(".jpg") ? "image/jpeg" : null;
    }

    /** Share targets ask for the display name and size; nothing else is exposed. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cur = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        cur.addRow(row);
        return cur;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
