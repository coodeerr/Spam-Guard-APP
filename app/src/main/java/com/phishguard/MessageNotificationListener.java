package com.phishguard;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class MessageNotificationListener extends NotificationListenerService {
    private static final String TAG = "PhishGuard.NotifListener";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageName = sbn.getPackageName();
        if (packageName == null) return;

        Context context = getApplicationContext();

        // 1. Ignore notifications from our own app to prevent loops
        if (packageName.equals(context.getPackageName())) {
            return;
        }

        // 2. Identify if the notification is from a messaging app (SMS/RCS)
        String defaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context);
        boolean isMessagingApp = (defaultSmsApp != null && packageName.equals(defaultSmsApp))
                || packageName.equals("com.google.android.apps.messaging")
                || packageName.equals("com.samsung.android.messaging")
                || packageName.equals("com.android.messaging")
                || packageName.equals("com.android.mms");

        if (!isMessagingApp) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        String sender = null;
        String body = null;

        // Try reading standard fields first
        CharSequence titleCharSeq = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (titleCharSeq != null) {
            sender = titleCharSeq.toString();
        }

        CharSequence textCharSeq = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (textCharSeq != null) {
            body = textCharSeq.toString();
        }

        // Try extracting from MessagingStyle if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Parcelable[] messages = (Parcelable[]) extras.get(Notification.EXTRA_MESSAGES);
            if (messages != null && messages.length > 0) {
                Bundle latestMessage = (Bundle) messages[messages.length - 1];
                CharSequence msgText = latestMessage.getCharSequence("text");
                if (msgText != null && msgText.length() > 0) {
                    body = msgText.toString();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Object senderPersonObj = latestMessage.get("sender_person");
                    if (senderPersonObj instanceof android.app.Person) {
                        android.app.Person senderPerson = (android.app.Person) senderPersonObj;
                        sender = senderPerson.getName().toString();
                    }
                }

                if (sender == null) {
                    CharSequence senderCharSeq = latestMessage.getCharSequence("sender");
                    if (senderCharSeq != null) {
                        sender = senderCharSeq.toString();
                    }
                }
            }
        }

        if (body == null || body.trim().isEmpty()) return;
        if (sender == null || sender.trim().isEmpty()) {
            sender = "Unknown Sender";
        }

        String finalBody = body.trim();
        String finalSender = sender.trim();

        // 3. Deduplicate (prevent duplicate scans if SMS receiver also fired for this message)
        MessageDatabase db = new MessageDatabase(context);
        if (db.isDuplicate(finalBody)) {
            Log.d(TAG, "Duplicate message ignored: " + finalBody);
            return;
        }

        Log.d(TAG, "Intercepted message from " + finalSender + ": " + finalBody);

        // 4. Analyze message
        SmsAnalyzer analyzer = new SmsAnalyzer(context);
        SmsAnalyzer.Result result = analyzer.analyze(finalBody, finalSender);

        Log.d(TAG, "Classification: " + result.label + " (" + result.confidence + "%) — " + result.reason);

        // 5. Show notification (Heads-up)
        NotificationHelper.show(context, finalSender, finalBody, result);

        // 6. Insert into database
        db.insert(finalSender, finalBody, result);
    }
}
