package com.phishguard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MessageDatabase — SQLite wrapper that stores every scanned SMS
 * with its classification result for display in MainActivity.
 */
public class MessageDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME    = "phishguard.db";
    private static final int    DB_VERSION = 1;
    private static final String TABLE      = "messages";

    public MessageDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "_id       INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "sender    TEXT, " +
            "body      TEXT, " +
            "label     TEXT, " +       // SPAM / SAFE
            "confidence INTEGER, " +
            "reason    TEXT, " +
            "timestamp TEXT" +
            ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ── Insert a scanned message ──────────────────────────────────────────────
    public void insert(String sender, String body, SmsAnalyzer.Result result) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("sender",     sender);
        cv.put("body",       body);
        cv.put("label",      result.label);
        cv.put("confidence", result.confidence);
        cv.put("reason",     result.reason);
        cv.put("timestamp",  new SimpleDateFormat("dd MMM, HH:mm", Locale.ENGLISH)
                                 .format(new Date()));
        db.insert(TABLE, null, cv);
        db.close();
     }
 
     public void updateStatus(String sender, String body, String newLabel, int confidence, String reason) {
         SQLiteDatabase db = getWritableDatabase();
         ContentValues cv = new ContentValues();
         cv.put("label",      newLabel);
         cv.put("confidence", confidence);
         cv.put("reason",     reason);
         db.update(TABLE, cv, "sender = ? AND body = ?", new String[]{sender, body});
         db.close();
     }

    public boolean isDuplicate(String body) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, new String[]{"body"}, null, null, null, null, "_id DESC", "3");
        boolean exists = false;
        while (c.moveToNext()) {
            String existingBody = c.getString(0);
            if (existingBody != null && existingBody.equals(body)) {
                exists = true;
                break;
            }
        }
        c.close();
        db.close();
        return exists;
    }

    // ── MessageItem model ─────────────────────────────────────────────────────
    public static class MessageItem {
        public final int    id;
        public final String sender, body, label, reason, timestamp;
        public final int    confidence;

        public MessageItem(int id, String sender, String body,
                           String label, int confidence, String reason, String timestamp) {
            this.id = id; this.sender = sender; this.body = body;
            this.label = label; this.confidence = confidence;
            this.reason = reason; this.timestamp = timestamp;
        }

        public boolean isSpam() { return "SPAM".equals(label); }
    }

    // ── Fetch all messages, newest first ─────────────────────────────────────
    public List<MessageItem> getAll() {
        List<MessageItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE, null, null, null, null, null, "_id DESC");
        while (c.moveToNext()) {
            list.add(new MessageItem(
                c.getInt(c.getColumnIndexOrThrow("_id")),
                c.getString(c.getColumnIndexOrThrow("sender")),
                c.getString(c.getColumnIndexOrThrow("body")),
                c.getString(c.getColumnIndexOrThrow("label")),
                c.getInt(c.getColumnIndexOrThrow("confidence")),
                c.getString(c.getColumnIndexOrThrow("reason")),
                c.getString(c.getColumnIndexOrThrow("timestamp"))
            ));
        }
        c.close();
        db.close();
        return list;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    public int countAll()  { return count(null); }
    public int countSpam() { return count("label = 'SPAM'"); }
    public int countSafe() { return count("label = 'SAFE'"); }

    private int count(String where) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE +
                (where != null ? " WHERE " + where : ""), null);
        int result = 0;
        if (c.moveToFirst()) result = c.getInt(0);
        c.close();
        db.close();
        return result;
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, null, null);
        db.close();
    }
}
