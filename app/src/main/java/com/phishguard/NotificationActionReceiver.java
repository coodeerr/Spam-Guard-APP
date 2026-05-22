package com.phishguard;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * NotificationActionReceiver — handles quick action clicks ("Mark Safe", "Report Spam")
 * directly from heads-up and system shade notifications in the background.
 */
public class NotificationActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String sender = intent.getStringExtra("sender");
        String body = intent.getStringExtra("body");
        String newLabel = intent.getStringExtra("new_label");
        int notifId = intent.getIntExtra("notif_id", -1);

        if (sender == null || body == null || newLabel == null) return;

        // 1. Update SQLite Database
        MessageDatabase db = new MessageDatabase(context);
        String reason = "SAFE".equals(newLabel) 
                ? "Manually marked secure by user." 
                : "Manually reported phishing by user.";
        db.updateStatus(sender, body, newLabel, 100, reason);

        // 2. Dismiss Notification
        if (notifId != -1) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(notifId);
            }
        }

        // 3. Confirm with Toast
        String message = "SAFE".equals(newLabel) 
                ? "Message marked as SAFE" 
                : "Message flagged as UNSAFE / SPAM";
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
