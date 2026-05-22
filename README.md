# PhishGuard — Android SMS Threat Detector
### Final Year Project (URP 4301) · SOA University, Bhubaneswar


---

## What this app does

PhishGuard registers a **BroadcastReceiver** at system-level priority so it intercepts
every incoming SMS *before* the default SMS app displays it. It then:

1. Extracts ML features from the message (URL analysis, keyword scoring, etc.)
2. Runs the classifier → **SPAM** or **SAFE** with a confidence score
3. Posts a **heads-up notification** (peeks over the screen like a WhatsApp alert)
4. Stores the result in a local SQLite database
5. Shows full history on the main dashboard

---

## How to open in Android Studio

1. Unzip `PhishGuard.zip`
2. Open Android Studio → **File → Open** → select the `PhishGuard` folder
3. Wait for Gradle sync to complete
4. Connect an Android device (API 23+) or start an emulator
5. Run → **▶ Run 'app'**
6. Grant **SMS** and **Notification** permissions when prompted

---

## Project Architecture

```
SmsReceiver (BroadcastReceiver, priority 999)
    │
    ├──► SmsAnalyzer.analyze(body, sender)
    │         │
    │         ├── URL feature extraction
    │         │     • presence of URL
    │         │     • suspicious TLD (.tk .xyz .ml etc.)
    │         │     • HTTP vs HTTPS
    │         │     • subdomain depth (nested domains)
    │         │     • typosquatting against known brands
    │         │     • '@' in URL (credential harvesting)
    │         │     • URL length > 75 chars
    │         │
    │         ├── Text / NLP features
    │         │     • urgency keyword score (35+ keywords)
    │         │     • brand impersonation in text
    │         │     • special character ratio
    │         │     • monetary prize pattern (Rs / ₹)
    │         │     • suspicious phone-number + urgency combo
    │         │
    │         └── Safe signals (reduces score)
    │               • OTP pattern from registered sender
    │
    ├──► NotificationHelper.show()   → heads-up notification
    └──► MessageDatabase.insert()    → SQLite history
```

### Classification

Each feature contributes a **score** (mirroring a Random Forest feature-importance
weighted prediction):

| Feature triggered | Score added |
|---|---|
| URL present | +10 |
| IP-based URL | +25 |
| Suspicious TLD | +20 |
| HTTP (not HTTPS) | +8 |
| Deep subdomain (>3) | +12 |
| Typosquatting detected | +18 |
| URL length > 75 | +7 |
| '@' in URL | +15 |
| Urgency keyword (each, max 4) | +7 |
| Brand impersonation + URL | +8 |
| Special-char ratio > 15% | +10 |
| Monetary prize claim | +15 |
| Phone number + urgency | +10 |
| Legitimate OTP from safe sender | −20 |

**Threshold: score ≥ 35 → SPAM**

---

## Swapping in your trained ML model

Once you've trained your Random Forest / SVM / XGBoost models in Python:

**Option A — Flask API (recommended for project demo)**

1. Export your model: `joblib.dump(model, 'phishguard_model.pkl')`
2. Wrap it in a Flask endpoint:
   ```python
   @app.route('/classify', methods=['POST'])
   def classify():
       msg = request.json['message']
       features = extract_features(msg)
       label = model.predict([features])[0]
       proba = model.predict_proba([features])[0].max()
       return jsonify({'label': label, 'confidence': int(proba*100)})
   ```
3. In `SmsAnalyzer.java`, replace the score logic with an HTTP call to your Flask server
4. Run Flask on your laptop and connect phone + laptop to the same Wi-Fi

**Option B — On-device (ONNX / TFLite)**

Export your sklearn model to ONNX format and run inference directly on device
using the ONNX Runtime Android library. No internet needed.

---

## Permissions explained

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Intercept incoming SMS before default app |
| `READ_SMS` | Read full message content |
| `POST_NOTIFICATIONS` | Show heads-up alerts |
| `INTERNET` | For future API-based model inference |
| `VIBRATE` | Vibration alert on phishing detection |

---

## Files

```
app/src/main/java/com/phishguard/
├── SmsReceiver.java      — BroadcastReceiver (the entry point)
├── SmsAnalyzer.java      — Feature extraction + scoring classifier
├── NotificationHelper.java — Heads-up notification builder
├── MessageDatabase.java  — SQLite storage & retrieval
└── MainActivity.java     — Dashboard UI (stats + message history)
```

---

## Testing

**On emulator:**  
Use the extended controls → Phone → SMS tab to send a test SMS to the emulator.

**On real device:**  
Use the "Test: Analyze a message manually" button in the app to paste any message and
see the classification + notification without needing an actual incoming SMS.

**Sample phishing messages to test:**
- `URGENT: Your SBI account is blocked. Verify at http://sbi-kyc-update.tk/login now`
- `You've won Rs 50,000! Call 9876543210 to claim your prize immediately`
- `Dear user, your HDFC KYC is incomplete. Update at http://192.168.1.1/verify`

**Sample safe messages:**
- `Your OTP for Flipkart is 847291. Valid for 10 minutes.`
- `Mom reaching home by 7, order pizza`
- `Your Amazon order has been shipped. Track at amazon.in`
