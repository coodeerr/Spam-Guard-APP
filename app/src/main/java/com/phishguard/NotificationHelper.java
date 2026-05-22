package com.phishguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * NotificationHelper — creates two channels (SPAM / SAFE) and posts
 * a heads-up (peeking) notification immediately when an SMS is classified.
 *
 * SPAM notification : red colour, high priority, vibration alert
 * SAFE notification : green colour, default priority
 */
public class NotificationHelper {

    private static final String CHANNEL_SPAM  = "phishguard_spam";
    private static final String CHANNEL_SAFE  = "phishguard_safe";
    private static int notifId = 1000;

    public static void show(Context ctx, String sender, String body, SmsAnalyzer.Result result) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        ensureChannels(nm);

        boolean isSpam = result.isSpam();
        boolean isSuspicious = "SUSPICIOUS".equals(result.label);

        // Intent opens MainActivity when tapped
        Intent openApp = new Intent(ctx, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Truncate body for display
        String preview = body.length() > 80 ? body.substring(0, 80) + "…" : body;

        String channel;
        String title;
        int color;
        int priority;
        String category;

        if (isSpam) {
            channel = CHANNEL_SPAM;
            title = "⚠ Unsafe SMS from " + sender;
            color = Color.parseColor("#EF4444"); // Vibrant Red
            priority = NotificationCompat.PRIORITY_HIGH;
            category = NotificationCompat.CATEGORY_ALARM;
        } else if (isSuspicious) {
            channel = CHANNEL_SPAM; // Use high importance channel to ensure warning is seen
            title = "⚠ Suspicious SMS from " + sender;
            color = Color.parseColor("#FBBF24"); // Amber/Yellow
            priority = NotificationCompat.PRIORITY_HIGH; // Borderline high priority
            category = NotificationCompat.CATEGORY_MESSAGE;
        } else {
            channel = CHANNEL_SAFE;
            title = "✓ Safe SMS from " + sender;
            color = Color.parseColor("#22C55E"); // Safe Green
            priority = NotificationCompat.PRIORITY_DEFAULT;
            category = NotificationCompat.CATEGORY_MESSAGE;
        }

        String bigText = preview + "\n\n" + result.reason;
        String subText = result.confidence + "% " + 
                (isSpam ? "threat" : (isSuspicious ? "suspicious" : "safe"));

        int activeNotifId = notifId++;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(bigText)
                        .setSummaryText(subText))
                .setColor(color)
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(category);

        // Interactive Quick Actions (Mark Safe / Report Phishing)
        Intent actionIntent = new Intent(ctx, NotificationActionReceiver.class);
        actionIntent.putExtra("sender", sender);
        actionIntent.putExtra("body", body);
        actionIntent.putExtra("notif_id", activeNotifId);

        if (isSpam || isSuspicious) {
            actionIntent.putExtra("new_label", "SAFE");
            PendingIntent actionPi = PendingIntent.getBroadcast(ctx, activeNotifId, actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_menu_save, "Mark Safe", actionPi);
        } else {
            actionIntent.putExtra("new_label", "SPAM");
            PendingIntent actionPi = PendingIntent.getBroadcast(ctx, activeNotifId, actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_delete, "Report Phishing", actionPi);
        }

        // Heads-up (peek) and vibration for Unsafe (Red) and Suspicious (Yellow)
        if (isSpam || isSuspicious) {
            builder.setDefaults(Notification.DEFAULT_VIBRATE)
                   .setVibrate(new long[]{0, 400, 200, 400})
                   .setFullScreenIntent(pi, true);   // force peek
        }

        nm.notify(activeNotifId, builder.build());
    }

    // ── Create notification channels (Android 8+) ────────────────────────────
    private static void ensureChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        // SPAM channel — high importance so it peeks
        NotificationChannel spam = new NotificationChannel(
                CHANNEL_SPAM,
                "Phishing Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        spam.setDescription("Alerts for suspected phishing or spam SMS");
        spam.setLightColor(Color.RED);
        spam.enableLights(true);
        spam.enableVibration(true);
        spam.setVibrationPattern(new long[]{0, 400, 200, 400});
        nm.createNotificationChannel(spam);

        // SAFE channel — default importance
        NotificationChannel safe = new NotificationChannel(
                CHANNEL_SAFE,
                "Safe Message Confirmations",
                NotificationManager.IMPORTANCE_DEFAULT);
        safe.setDescription("Confirmations for messages that appear legitimate");
        safe.setLightColor(Color.GREEN);
        nm.createNotificationChannel(safe);
    }
}
