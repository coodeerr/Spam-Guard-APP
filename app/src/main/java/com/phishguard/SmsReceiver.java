package com.phishguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * SmsReceiver — registered at priority 999 so it fires BEFORE the default SMS app.
 * Intercepts every incoming SMS, runs the SmsAnalyzer, and fires a notification.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "PhishGuard.SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;

        // Concatenate multi-part SMS
        StringBuilder body   = new StringBuilder();
        String        sender = parts[0].getDisplayOriginatingAddress();
        for (SmsMessage part : parts) body.append(part.getMessageBody());

        String fullBody = body.toString().trim();
        Log.d(TAG, "Received SMS from " + sender + ": " + fullBody);

        // ── Classify ────────────────────────────────────────────────────────
        SmsAnalyzer analyzer = new SmsAnalyzer(context);
        SmsAnalyzer.Result result = analyzer.analyze(fullBody, sender);

        Log.d(TAG, "Classification: " + result.label + " (" + result.confidence + "%) — " + result.reason);

        // ── Notify user immediately ──────────────────────────────────────────
        NotificationHelper.show(context, sender, fullBody, result);

        // ── Persist to local database ────────────────────────────────────────
        MessageDatabase db = new MessageDatabase(context);
        db.insert(sender, fullBody, result);
    }
}
