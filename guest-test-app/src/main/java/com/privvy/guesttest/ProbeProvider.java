package com.privvy.guesttest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;
import java.util.TreeMap;

public class ProbeProvider extends ContentProvider {

    private static final TreeMap<Long, String> ROWS = new TreeMap<>();
    private static long nextId = 1;
    private static int calls;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection : new String[]{"_id", "value"};
        MatrixCursor cursor = new MatrixCursor(columns);
        Long selectedId = selection != null && selectionArgs != null && selectionArgs.length > 0
                ? Long.valueOf(selectionArgs[0]) : null;
        synchronized (ROWS) {
            for (Map.Entry<Long, String> row : ROWS.entrySet()) {
                if (selectedId != null && !selectedId.equals(row.getKey())) continue;
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    values[i] = "_id".equals(columns[i]) ? row.getKey() : row.getValue();
                }
                cursor.addRow(values);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        long id;
        synchronized (ROWS) {
            id = nextId++;
            ROWS.put(id, values.getAsString("value"));
        }
        return Uri.withAppendedPath(uri, Long.toString(id));
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (selection == null) {
            synchronized (ROWS) {
                int count = ROWS.size();
                ROWS.clear();
                return count;
            }
        }
        return ROWS.remove(Long.valueOf(selectionArgs[0])) != null ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (selection == null || selectionArgs == null || selectionArgs.length == 0) return 0;
        return ROWS.replace(Long.valueOf(selectionArgs[0]), values.getAsString("value")) != null ? 1 : 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.privvy.guesttest.item";
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        synchronized (ROWS) {
            calls++;
            result.putString("result", "PASS provider=" + authority + " method=" + method
                    + " calls=" + calls + " rows=" + ROWS.size());
        }
        return result;
    }

    public static void reset() {
        synchronized (ROWS) {
            ROWS.clear();
            nextId = 1;
            calls = 0;
        }
    }
}
