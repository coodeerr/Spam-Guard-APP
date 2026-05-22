package com.phishguard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.Locale;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;


/**
 * MainActivity — dashboard showing:
 *   • Live stats (total scanned / threats / safe)
 *   • Full history of classified messages as scrollable cards
 *   • Permission setup wizard
 *   • Manual "test a message" button for demo purposes
 */
public class MainActivity extends AppCompatActivity {

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int themeColor(String darkHex, String lightHex) {
        return Color.parseColor(isDarkTheme() ? darkHex : lightHex);
    }


    private MessageDatabase db;
    private LinearLayout    msgContainer;
    private TextView        tvTotal, tvSpam, tvSafe, tvRate;
    private TextView        tvStatus;
    private int             currentFilter = 0; // 0 = All Scans, 1 = Phishing Only, 2 = Safe Only
    private InboxHealthGauge healthGauge;
    private String           searchQuery = "";

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = !result.containsValue(false);
            updateStatusBanner(allGranted);
            if (allGranted) refresh();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE);
        int mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
        super.onCreate(savedInstanceState);

        // Prevent layout flicker by setting the dynamic background drawable to window directly
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(themeColor("#0D0F14", "#F3F4F6")));
        updateSystemBars();

        db = new MessageDatabase(this);

        // ── Build UI programmatically (no XML needed for simplicity) ─────────
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(themeColor("#0D0F14", "#F3F4F6"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(themeColor("#0D0F14", "#F3F4F6"));

        // Header
        root.addView(makeHeader());

        // Status banner
        tvStatus = new TextView(this);
        tvStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(themeColor("#FFFFFF", "#000000"));
        LinearLayout.LayoutParams bannerParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerParams.setMargins(0, dp(8), 0, dp(16));
        tvStatus.setLayoutParams(bannerParams);
        tvStatus.setBackgroundColor(themeColor("#1E2330", "#E5E7EB"));
        root.addView(tvStatus);

        // Top Action Buttons (Test manual and Clear history)
        root.addView(makeTopActionRow());

        // Circular safety indicator dashboard gauge
        LinearLayout gaugeCard = new LinearLayout(this);
        gaugeCard.setOrientation(LinearLayout.VERTICAL);
        gaugeCard.setGravity(Gravity.CENTER);
        gaugeCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        android.graphics.drawable.GradientDrawable gaugeBg = new android.graphics.drawable.GradientDrawable();
        gaugeBg.setColor(themeColor("#161A22", "#FFFFFF"));
        gaugeBg.setCornerRadius(dp(16));
        gaugeCard.setBackground(gaugeBg);
        
        LinearLayout.LayoutParams gaugeP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gaugeP.setMargins(0, 0, 0, dp(16));
        gaugeCard.setLayoutParams(gaugeP);
        
        healthGauge = new InboxHealthGauge(this);
        gaugeCard.addView(healthGauge);
        root.addView(gaugeCard);

        // Stats row
        root.addView(makeStatsRow());

        // Divider label and Filter Spinner
        root.addView(makeRecentScansHeader());

        // Search Input Field
        EditText etSearch = new EditText(this);
        etSearch.setHint("Search messages or senders…");
        etSearch.setHintTextColor(themeColor("#6B7280", "#9CA3AF"));
        etSearch.setTextColor(themeColor("#FFFFFF", "#111827"));
        etSearch.setSingleLine(true);
        etSearch.setTextSize(14);
        etSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        
        android.graphics.drawable.GradientDrawable searchBg = new android.graphics.drawable.GradientDrawable();
        searchBg.setColor(themeColor("#161A22", "#FFFFFF"));
        searchBg.setStroke(dp(1), themeColor("#374151", "#D1D5DB"));
        searchBg.setCornerRadius(dp(8));
        etSearch.setBackground(searchBg);
        
        LinearLayout.LayoutParams searchP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchP.setMargins(0, 0, 0, dp(12));
        etSearch.setLayoutParams(searchP);
        
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString().trim();
                refresh();
            }
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        root.addView(etSearch);

        // Message cards container
        msgContainer = new LinearLayout(this);
        msgContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(msgContainer);

        scroll.addView(root);
        setContentView(scroll);

        checkPermissions();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ── Permission check ──────────────────────────────────────────────────────
    private boolean isNotificationListenerEnabled() {
        String pkgName = getPackageName();
        String flat = android.provider.Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (flat != null) {
            String[] names = flat.split(":");
            for (String name : names) {
                android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(name);
                if (cn != null && pkgName.equals(cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void showNotificationListenerDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Enable Notification Access")
            .setMessage("To scan messages from all senders (including RCS and saved contacts) in real time, PhishGuard requires Notification Access.\n\nPlease find 'Spam Guard' in the next screen and switch it ON.")
            .setPositiveButton("Go to Settings", (d, w) -> {
                try {
                    Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open settings. Please enable manually.", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Permission check ──────────────────────────────────────────────────────
    private void checkPermissions() {
        boolean smsOk = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifOk = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        boolean listenerOk = isNotificationListenerEnabled();

        if (!smsOk || !notifOk) {
            List<String> needed = new java.util.ArrayList<>();
            if (!smsOk) needed.add(Manifest.permission.RECEIVE_SMS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            permLauncher.launch(needed.toArray(new String[0]));
        } else if (!listenerOk) {
            updateStatusBanner(false);
        } else {
            updateStatusBanner(true);
        }
    }

    private void updateStatusBanner(boolean active) {
        if (active) {
            tvStatus.setText(R.string.status_active);
            tvStatus.setBackgroundColor(themeColor("#0F2A1A", "#DCFCE7"));
            tvStatus.setTextColor(themeColor("#4ADE80", "#16A34A"));
            tvStatus.setOnClickListener(null);
        } else {
            boolean smsOk = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
            boolean notifOk = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifOk = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            boolean listenerOk = isNotificationListenerEnabled();

            if (!smsOk || !notifOk) {
                tvStatus.setText(R.string.status_permissions_required);
                tvStatus.setBackgroundColor(themeColor("#2A1A1A", "#FEE2E2"));
                tvStatus.setTextColor(themeColor("#F87171", "#DC2626"));
                tvStatus.setOnClickListener(v -> checkPermissions());
            } else if (!listenerOk) {
                tvStatus.setText("⚠ Notification Access required — tap to scan RCS \u0026 contacts' messages");
                tvStatus.setBackgroundColor(themeColor("#2A241A", "#FEF3C7")); // Elegant Amber
                tvStatus.setTextColor(themeColor("#F59E0B", "#D97706"));
                tvStatus.setOnClickListener(v -> showNotificationListenerDialog());
            }
        }
    }

    // ── Refresh stats and message list ───────────────────────────────────────
    private void refresh() {
        int total = db.countAll(), spam = db.countSpam(), safe = db.countSafe();
        tvTotal.setText(String.valueOf(total));
        tvSpam.setText(String.valueOf(spam));
        tvSafe.setText(String.valueOf(safe));
        String rate = total > 0 ? getString(R.string.rate_format, (int)((spam * 100.0) / total)) : getString(R.string.rate_empty);
        tvRate.setText(rate);

        // Update Inbox Health Gauge Safety Score
        int health = (total > 0) ? (safe * 100 / total) : 100;
        if (healthGauge != null) {
            healthGauge.setHealth(health);
        }

        msgContainer.removeAllViews();
        List<MessageDatabase.MessageItem> items = db.getAll();
        boolean hasVisibleItems = false;
        
        for (MessageDatabase.MessageItem item : items) {
            boolean isSpamOrSuspicious = item.isSpam() || "SUSPICIOUS".equals(item.label);
            if (currentFilter == 1 && !isSpamOrSuspicious) continue; // Phishing & Suspicious
            if (currentFilter == 2 && !item.label.equals("SAFE")) continue;  // Safe only

            // Search query live filter
            if (searchQuery != null && !searchQuery.isEmpty()) {
                String q = searchQuery.toLowerCase(Locale.ENGLISH);
                boolean matchesSender = item.sender != null && item.sender.toLowerCase(Locale.ENGLISH).contains(q);
                boolean matchesBody = item.body != null && item.body.toLowerCase(Locale.ENGLISH).contains(q);
                if (!matchesSender && !matchesBody) {
                    continue;
                }
            }
            
            msgContainer.addView(makeMsgCard(item));
            hasVisibleItems = true;
        }
        
        if (!hasVisibleItems) {
            TextView empty = new TextView(this);
            if (items.isEmpty()) {
                empty.setText(R.string.empty_history);
            } else if (currentFilter == 1) {
                empty.setText("No phishing messages found.");
            } else if (currentFilter == 2) {
                empty.setText("No safe messages found.");
            }
            empty.setTextColor(themeColor("#6B7280", "#4B5563"));
            empty.setTextSize(13);
            empty.setPadding(0, dp(24), 0, dp(24));
            empty.setGravity(Gravity.CENTER);
            msgContainer.addView(empty);
        }
    }

    // ── Build a message card view ─────────────────────────────────────────────
    private View makeMsgCard(MessageDatabase.MessageItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(themeColor("#161A22", "#FFFFFF"));

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cp);

        // Left accent bar
        card.setBackground(null);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setLayoutParams(cp);

        View bar = new View(this);
        LinearLayout.LayoutParams barP = new LinearLayout.LayoutParams(dp(4),
                ViewGroup.LayoutParams.MATCH_PARENT);
        barP.setMargins(0, 0, dp(10), 0);
        bar.setLayoutParams(barP);
        
        int barColor;
        if (item.isSpam()) {
            barColor = themeColor("#EF4444", "#DC2626"); // Red
        } else if (item.label.equals("SUSPICIOUS")) {
            barColor = themeColor("#FBBF24", "#D97706"); // Amber
        } else {
            barColor = themeColor("#22C55E", "#16A34A"); // Green
        }
        bar.setBackgroundColor(barColor);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // Row 1: sender + badge
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvSender = new TextView(this);
        tvSender.setText(item.sender);
        tvSender.setTextColor(themeColor("#FFFFFF", "#000000"));
        tvSender.setTextSize(13);
        tvSender.setTypeface(null, android.graphics.Typeface.BOLD);
        tvSender.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView badge = new TextView(this);
        if (item.isSpam()) {
            badge.setText("⚠ UNSAFE");
            badge.setTextColor(themeColor("#FCA5A5", "#991B1B"));
            badge.setBackgroundColor(themeColor("#2A1010", "#FEE2E2"));
        } else if (item.label.equals("SUSPICIOUS")) {
            badge.setText("⚠ SUSPICIOUS");
            badge.setTextColor(themeColor("#FDE68A", "#92400E"));
            badge.setBackgroundColor(themeColor("#2A2410", "#FEF3C7"));
        } else {
            badge.setText("✓ SAFE");
            badge.setTextColor(themeColor("#86EFAC", "#166534"));
            badge.setBackgroundColor(themeColor("#0F2A1A", "#DCFCE7"));
        }
        badge.setTextSize(10);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));

        row1.addView(tvSender);
        row1.addView(badge);

        // Body preview
        String preview = item.body.length() > 90
                ? item.body.substring(0, 90) + "…" : item.body;
        TextView tvBody = new TextView(this);
        tvBody.setText(preview);
        tvBody.setTextColor(themeColor("#D1D5DB", "#1F2937"));
        tvBody.setTextSize(12);
        tvBody.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyP.setMargins(0, dp(4), 0, dp(4));
        tvBody.setLayoutParams(bodyP);

        // Reason
        TextView tvReason = new TextView(this);
        tvReason.setText(getString(R.string.msg_reason_format, item.confidence, item.reason));
        tvReason.setTextSize(11);
        tvReason.setTypeface(null, android.graphics.Typeface.BOLD);
        
        int reasonColor;
        if (item.isSpam()) {
            reasonColor = themeColor("#F87171", "#DC2626");
        } else if (item.label.equals("SUSPICIOUS")) {
            reasonColor = themeColor("#FBBF24", "#D97706");
        } else {
            reasonColor = themeColor("#4ADE80", "#16A34A");
        }
        tvReason.setTextColor(reasonColor);

        // Timestamp
        TextView tvTime = new TextView(this);
        tvTime.setText(item.timestamp);
        tvTime.setTextSize(10);
        tvTime.setTextColor(themeColor("#4B5563", "#9CA3AF"));
        LinearLayout.LayoutParams timeP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeP.setMargins(0, dp(4), 0, 0);
        tvTime.setLayoutParams(timeP);

        content.addView(row1);
        content.addView(tvBody);
        content.addView(tvReason);
        content.addView(tvTime);

        LinearLayout cardBg = new LinearLayout(this);
        cardBg.setOrientation(LinearLayout.HORIZONTAL);
        
        android.graphics.drawable.GradientDrawable cardDrawable = new android.graphics.drawable.GradientDrawable();
        cardDrawable.setColor(themeColor("#161A22", "#FFFFFF"));
        cardDrawable.setCornerRadius(dp(12));
        cardDrawable.setStroke(dp(1), themeColor("#2D3748", "#E2E8F0"));
        cardBg.setBackground(cardDrawable);
        
        cardBg.setPadding(dp(12), dp(12), dp(12), dp(12));
        cardBg.setLayoutParams(cp);
        cardBg.addView(bar);
        cardBg.addView(content);

        cardBg.setClickable(true);
        cardBg.setFocusable(true);
        cardBg.setOnClickListener(v -> showMessageDetailDialog(item));

        return cardBg;
    }

    // ── Top action buttons (Test manual & Clear history) ──────────────────────
    private android.graphics.drawable.Drawable makeButtonBackground(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private View makeTopActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, 0, 0, dp(16));
        row.setLayoutParams(rp);
        
        // Test button
        TextView btnTest = new TextView(this);
        btnTest.setText("Manual Test");
        btnTest.setGravity(Gravity.CENTER);
        btnTest.setBackground(makeButtonBackground(themeColor("#1E2330", "#E5E7EB"), 8));
        btnTest.setTextColor(themeColor("#60A5FA", "#2563EB"));
        btnTest.setTextSize(13);
        btnTest.setTypeface(null, android.graphics.Typeface.BOLD);
        btnTest.setClickable(true);
        btnTest.setFocusable(true);
        btnTest.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(44), 1);
        p1.setMargins(0, 0, dp(8), 0);
        btnTest.setLayoutParams(p1);
        btnTest.setOnClickListener(v -> showTestDialog());
        
        // Clear button
        TextView btnClear = new TextView(this);
        btnClear.setText("Clear History");
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setBackground(makeButtonBackground(themeColor("#2A1A1A", "#FEE2E2"), 8));
        btnClear.setTextColor(themeColor("#F87171", "#DC2626"));
        btnClear.setTextSize(13);
        btnClear.setTypeface(null, android.graphics.Typeface.BOLD);
        btnClear.setClickable(true);
        btnClear.setFocusable(true);
        btnClear.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(44), 1);
        p2.setMargins(0, 0, 0, 0);
        btnClear.setLayoutParams(p2);
        btnClear.setOnClickListener(v -> showClearConfirmDialog());
        
        row.addView(btnTest);
        row.addView(btnClear);
        return row;
    }

    private void showClearConfirmDialog() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear History?")
            .setMessage("This will delete all scanned message logs permanently. This action cannot be undone.")
            .setPositiveButton("Delete All", (d, w) -> {
                db.clearAll();
                refresh();
                Toast.makeText(this, "All scanning history cleared", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private View makeRecentScansHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, dp(6));
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText("RECENT SCANS");
        tv.setTextColor(themeColor("#4B5563", "#9CA3AF"));
        tv.setTextSize(11);
        tv.setLetterSpacing(0.1f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tv);

        Spinner spinner = new Spinner(this);
        String[] options = {"All Scans", "Phishing Only", "Safe Only"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextColor(themeColor("#6B7280", "#4B5563"));
                    tv.setTextSize(12);
                    tv.setGravity(Gravity.END);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setBackgroundColor(themeColor("#161A22", "#FFFFFF"));
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextColor(themeColor("#FFFFFF", "#000000"));
                    tv.setTextSize(13);
                    tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(currentFilter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (currentFilter != position) {
                    currentFilter = position;
                    refresh();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        row.addView(spinner);
        return row;
    }

    private void showTestDialog() {
        EditText et = new EditText(this);
        et.setHint(R.string.hint_test_sms);
        et.setTextColor(themeColor("#FFFFFF", "#000000"));
        et.setHintTextColor(themeColor("#888888", "#555555"));
        et.setBackgroundColor(themeColor("#1E2330", "#E5E7EB"));
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.dialog_title_test)
            .setView(et)
            .setPositiveButton(R.string.btn_analyze, (d, w) -> {
                String msg = et.getText().toString().trim();
                if (msg.isEmpty()) return;
                SmsAnalyzer.Result result =
                        new SmsAnalyzer(this).analyze(msg, "TEST-SENDER");
                NotificationHelper.show(this, "TEST-SENDER", msg, result);
                db.insert("TEST-SENDER", msg, result);
                refresh();
                Toast.makeText(this,
                        getString(R.string.test_result_toast_format, result.label, result.confidence, result.reason),
                        Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void showMessageDetailDialog(final MessageDatabase.MessageItem item) {
        LinearLayout detailLayout = new LinearLayout(this);
        detailLayout.setOrientation(LinearLayout.VERTICAL);
        detailLayout.setPadding(dp(20), dp(20), dp(20), dp(20));

        android.graphics.drawable.GradientDrawable dialogBg = new android.graphics.drawable.GradientDrawable();
        dialogBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        dialogBg.setCornerRadius(dp(16)); // Beautiful rounded card
        dialogBg.setColor(themeColor("#161A22", "#FFFFFF"));
        detailLayout.setBackground(dialogBg);

        // Sender Row
        TextView tvSender = new TextView(this);
        tvSender.setText(item.sender);
        tvSender.setTextColor(themeColor("#FFFFFF", "#000000"));
        tvSender.setTextSize(18);
        tvSender.setTypeface(null, android.graphics.Typeface.BOLD);
        detailLayout.addView(tvSender);

        // Badge & Time Row
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brp.setMargins(0, dp(6), 0, dp(12));
        badgeRow.setLayoutParams(brp);

        TextView badge = new TextView(this);
        if (item.isSpam()) {
            badge.setText(R.string.badge_phishing);
            badge.setTextColor(themeColor("#FCA5A5", "#991B1B"));
            badge.setBackgroundColor(themeColor("#2A1010", "#FEE2E2"));
        } else if ("SUSPICIOUS".equals(item.label)) {
            badge.setText(R.string.badge_suspicious);
            badge.setTextColor(themeColor("#FDE68A", "#92400E"));
            badge.setBackgroundColor(themeColor("#2A2410", "#FEF3C7"));
        } else {
            badge.setText(R.string.badge_safe);
            badge.setTextColor(themeColor("#86EFAC", "#166534"));
            badge.setBackgroundColor(themeColor("#0F2A1A", "#DCFCE7"));
        }
        badge.setTextSize(11);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badgeRow.addView(badge);

        TextView tvTime = new TextView(this);
        tvTime.setText(item.timestamp);
        tvTime.setTextSize(11);
        tvTime.setTextColor(themeColor("#6B7280", "#4B5563"));
        tvTime.setPadding(dp(12), 0, 0, 0);
        badgeRow.addView(tvTime);

        detailLayout.addView(badgeRow);

        // Scrollable Message Body Container
        ScrollView bodyScroll = new ScrollView(this);
        LinearLayout.LayoutParams bsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bsp.setMargins(0, 0, 0, dp(16));
        bodyScroll.setLayoutParams(bsp);

        TextView tvBody = new TextView(this);
        tvBody.setText(item.body);
        tvBody.setTextColor(themeColor("#D1D5DB", "#111827"));
        tvBody.setTextSize(14);
        tvBody.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBody.setLineSpacing(0f, 1.2f);
        bodyScroll.addView(tvBody);
        detailLayout.addView(bodyScroll);

        // Reason Card
        LinearLayout reasonCard = new LinearLayout(this);
        reasonCard.setOrientation(LinearLayout.VERTICAL);
        reasonCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        
        android.graphics.drawable.GradientDrawable rcBg = new android.graphics.drawable.GradientDrawable();
        rcBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        rcBg.setCornerRadius(dp(8));
        
        int rcBgColor;
        int reasonTitleColor;
        int reasonTextColor;
        
        if (item.isSpam()) {
            rcBgColor = themeColor("#2A1010", "#FEE2E2");
            reasonTitleColor = themeColor("#F87171", "#DC2626");
            reasonTextColor = themeColor("#FCA5A5", "#991B1B");
        } else if ("SUSPICIOUS".equals(item.label)) {
            rcBgColor = themeColor("#2A2410", "#FEF3C7");
            reasonTitleColor = themeColor("#FBBF24", "#D97706");
            reasonTextColor = themeColor("#FDE68A", "#92400E");
        } else {
            rcBgColor = themeColor("#0F2A1A", "#DCFCE7");
            reasonTitleColor = themeColor("#4ADE80", "#16A34A");
            reasonTextColor = themeColor("#86EFAC", "#166534");
        }
        
        rcBg.setColor(rcBgColor);
        reasonCard.setBackground(rcBg);
        
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rcp.setMargins(0, 0, 0, dp(16));
        reasonCard.setLayoutParams(rcp);

        TextView tvReasonTitle = new TextView(this);
        tvReasonTitle.setText("SCAN ANALYSIS");
        tvReasonTitle.setTextSize(9);
        tvReasonTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvReasonTitle.setTextColor(reasonTitleColor);
        
        TextView tvReason = new TextView(this);
        tvReason.setText(getString(R.string.msg_reason_format, item.confidence, item.reason));
        tvReason.setTextSize(12);
        tvReason.setTextColor(reasonTextColor);
        tvReason.setTypeface(null, android.graphics.Typeface.BOLD);
        tvReason.setPadding(0, dp(4), 0, 0);

        reasonCard.addView(tvReasonTitle);
        reasonCard.addView(tvReason);
        detailLayout.addView(reasonCard);

        // 1. Dynamic Heuristic Threat Chips
        SmsAnalyzer analyzer = new SmsAnalyzer(this);
        SmsAnalyzer.Result result = analyzer.analyze(item.body, item.sender);
        if (result.signals != null && !result.signals.isEmpty()) {
            TextView signalLabel = new TextView(this);
            signalLabel.setText("TRIGGERED THREAT SIGNALS");
            signalLabel.setTextColor(themeColor("#4B5563", "#9CA3AF"));
            signalLabel.setTextSize(9);
            signalLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMargins(0, 0, 0, dp(4));
            signalLabel.setLayoutParams(slp);
            detailLayout.addView(signalLabel);

            HorizontalScrollView chipScroll = new HorizontalScrollView(this);
            chipScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams csp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            csp.setMargins(0, 0, 0, dp(12));
            chipScroll.setLayoutParams(csp);

            LinearLayout chipContainer = new LinearLayout(this);
            chipContainer.setOrientation(LinearLayout.HORIZONTAL);
            
            for (String sig : result.signals) {
                TextView chip = new TextView(this);
                chip.setText(sig);
                chip.setTextSize(10);
                chip.setPadding(dp(8), dp(4), dp(8), dp(4));
                
                int chipBgColor;
                int chipTextColor;
                
                String lowerSig = sig.toLowerCase(Locale.ENGLISH);
                if (lowerSig.contains("whitelisted") || lowerSig.contains("trusted") || lowerSig.contains("suffix -g") || lowerSig.contains("suffix -t")) {
                    chipBgColor = themeColor("#0F2A1A", "#DCFCE7");
                    chipTextColor = themeColor("#4ADE80", "#16A34A");
                } else if (lowerSig.contains("insecure") || lowerSig.contains("ip address") || lowerSig.contains("suspicious tld") || lowerSig.contains("phishing") || lowerSig.contains("blocked")) {
                    chipBgColor = themeColor("#2A1010", "#FEE2E2");
                    chipTextColor = themeColor("#F87171", "#DC2626");
                } else {
                    chipBgColor = themeColor("#2A2410", "#FEF3C7");
                    chipTextColor = themeColor("#FBBF24", "#D97706");
                }
                
                android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
                chipBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                chipBg.setCornerRadius(dp(12));
                chipBg.setColor(chipBgColor);
                chip.setBackground(chipBg);
                chip.setTextColor(chipTextColor);
                
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 0, dp(6), 0);
                chip.setLayoutParams(clp);
                
                chipContainer.addView(chip);
            }
            chipScroll.addView(chipContainer);
            detailLayout.addView(chipScroll);
        }

        // Create Custom Dialog beforehand to dismiss programmatically inside click listeners
        final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(detailLayout)
            .setPositiveButton("Close", null)
            .create();

        // 2. Custom Whitelist & Blacklist buttons row
        final android.content.SharedPreferences sPrefs = getSharedPreferences("phishguard_prefs", MODE_PRIVATE);
        final java.util.Set<String> whitelist = new java.util.HashSet<>(sPrefs.getStringSet("custom_whitelist", new java.util.HashSet<>()));
        final java.util.Set<String> blacklist = new java.util.HashSet<>(sPrefs.getStringSet("custom_blacklist", new java.util.HashSet<>()));
        final String senderUpper = item.sender.toUpperCase(Locale.ENGLISH);
        boolean isWhitelisted = whitelist.contains(senderUpper);
        boolean isBlacklisted = blacklist.contains(senderUpper);

        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap1.setMargins(0, 0, 0, dp(10));
        actionRow1.setLayoutParams(ap1);

        Button btnBlock = new Button(this);
        btnBlock.setText(isBlacklisted ? "Blocked" : "Block Sender");
        btnBlock.setTextSize(11);
        btnBlock.setBackground(makeButtonBackground(themeColor("#2A1A1A", "#FEE2E2"), 6));
        btnBlock.setTextColor(themeColor("#F87171", "#DC2626"));
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bp1.setMargins(0, 0, dp(6), 0);
        btnBlock.setLayoutParams(bp1);
        btnBlock.setEnabled(!isBlacklisted);
        btnBlock.setOnClickListener(v -> {
            whitelist.remove(senderUpper);
            blacklist.add(senderUpper);
            sPrefs.edit().putStringSet("custom_whitelist", whitelist)
                         .putStringSet("custom_blacklist", blacklist).apply();
            db.updateStatus(item.sender, item.body, "SPAM", 100, "Blocked sender (flagged by user).");
            dialog.dismiss();
            refresh();
            Toast.makeText(this, "Sender blocked permanently", Toast.LENGTH_SHORT).show();
        });

        Button btnTrust = new Button(this);
        btnTrust.setText(isWhitelisted ? "Trusted" : "Always Trust");
        btnTrust.setTextSize(11);
        btnTrust.setBackground(makeButtonBackground(themeColor("#0F2A1A", "#DCFCE7"), 6));
        btnTrust.setTextColor(themeColor("#4ADE80", "#16A34A"));
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btnTrust.setLayoutParams(bp2);
        btnTrust.setEnabled(!isWhitelisted);
        btnTrust.setOnClickListener(v -> {
            blacklist.remove(senderUpper);
            whitelist.add(senderUpper);
            sPrefs.edit().putStringSet("custom_whitelist", whitelist)
                         .putStringSet("custom_blacklist", blacklist).apply();
            db.updateStatus(item.sender, item.body, "SAFE", 100, "Verified secure sender (trusted by user).");
            dialog.dismiss();
            refresh();
            Toast.makeText(this, "Sender whitelisted permanently", Toast.LENGTH_SHORT).show();
        });

        actionRow1.addView(btnBlock);
        actionRow1.addView(btnTrust);
        detailLayout.addView(actionRow1);

        // 3. Direct User Feedback Correction Row
        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setLayoutParams(ap1);

        Button btnCorrect = new Button(this);
        final boolean isCurrentSpam = "SPAM".equals(item.label) || "SUSPICIOUS".equals(item.label);
        btnCorrect.setText(isCurrentSpam ? "Mark as Safe" : "Report Phishing");
        btnCorrect.setTextSize(11);
        
        int correctBg = isCurrentSpam ? themeColor("#0F2A1A", "#DCFCE7") : themeColor("#2A1A1A", "#FEE2E2");
        int correctText = isCurrentSpam ? themeColor("#4ADE80", "#16A34A") : themeColor("#F87171", "#DC2626");
        
        btnCorrect.setBackground(makeButtonBackground(correctBg, 6));
        btnCorrect.setTextColor(correctText);
        btnCorrect.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        
        btnCorrect.setOnClickListener(v -> {
            String updatedLabel = isCurrentSpam ? "SAFE" : "SPAM";
            String updatedReason = isCurrentSpam ? "Manually corrected as safe by user." : "Manually reported as phishing by user.";
            db.updateStatus(item.sender, item.body, updatedLabel, 100, updatedReason);
            dialog.dismiss();
            refresh();
            Toast.makeText(this, "Message rating updated successfully", Toast.LENGTH_SHORT).show();
        });

        actionRow2.addView(btnCorrect);
        detailLayout.addView(actionRow2);

        dialog.show();

        // Polish AlertDialog standard styling to match transparent custom dialog cards
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private View makeHeader() {
        LinearLayout headerRoot = new LinearLayout(this);
        headerRoot.setOrientation(LinearLayout.HORIZONTAL);
        headerRoot.setGravity(Gravity.CENTER_VERTICAL);
        headerRoot.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText(R.string.app_title);
        title.setTextColor(themeColor("#FFFFFF", "#000000"));
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView sub = new TextView(this);
        sub.setText(R.string.app_subtitle);
        sub.setTextColor(themeColor("#6B7280", "#4B5563"));
        sub.setTextSize(12);

        h.addView(title);
        h.addView(sub);
        
        // Custom Pill-shaped Switcher Container (Glassmorphic inspired)
        LinearLayout switchContainer = new LinearLayout(this);
        switchContainer.setOrientation(LinearLayout.HORIZONTAL);
        switchContainer.setGravity(Gravity.CENTER_VERTICAL);
        switchContainer.setPadding(dp(12), dp(6), dp(12), dp(6));
        
        android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
        containerBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        containerBg.setCornerRadius(dp(20)); // Elegant Pill shape
        containerBg.setColor(themeColor("#161A22", "#E5E7EB"));
        switchContainer.setBackground(containerBg);
        
        TextView themeIcon = new TextView(this);
        themeIcon.setText(isDarkTheme() ? "🌙" : "☀️");
        themeIcon.setTextSize(16);
        themeIcon.setPadding(0, 0, dp(6), 0);
        
        androidx.appcompat.widget.SwitchCompat themeSwitch = new androidx.appcompat.widget.SwitchCompat(this);
        themeSwitch.setChecked(isDarkTheme());
        
        // Dynamic gold-and-slate styling for the switch to match sun/moon aesthetic
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked }, // checked (dark mode)
            new int[] { -android.R.attr.state_checked } // unchecked (light mode)
        };
        
        int[] thumbColors = new int[] {
            Color.parseColor("#FBBF24"), // Gold/Sun yellow for checked state
            Color.parseColor("#4B5563")  // Slate gray/Moon-like for unchecked state
        };
        
        int[] trackColors = new int[] {
            Color.parseColor("#374151"), // Slate gray track when checked
            Color.parseColor("#D1D5DB")  // Soft light gray track when unchecked
        };
        
        themeSwitch.setThumbTintList(new android.content.res.ColorStateList(states, thumbColors));
        themeSwitch.setTrackTintList(new android.content.res.ColorStateList(states, trackColors));
        
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int newMode = isChecked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO;
            SharedPreferences prefs = getSharedPreferences("theme_prefs", MODE_PRIVATE);
            prefs.edit().putInt("theme_mode", newMode).apply();
            AppCompatDelegate.setDefaultNightMode(newMode);
        });

        switchContainer.addView(themeIcon);
        switchContainer.addView(themeSwitch);

        headerRoot.addView(h);
        headerRoot.addView(switchContainer);

        return headerRoot;
    }

    private LinearLayout makeStatsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(0, dp(8), 0, dp(16));
        row.setLayoutParams(rp);
        row.setWeightSum(4);

        tvTotal = addStatCell(row, getString(R.string.stat_scanned), "0", "#E8EAF0");
        tvSpam  = addStatCell(row, getString(R.string.stat_threats), "0", "#F87171");
        tvSafe  = addStatCell(row, getString(R.string.stat_safe),    "0", "#4ADE80");
        tvRate  = addStatCell(row, getString(R.string.stat_rate),    getString(R.string.rate_empty), "#60A5FA");
        return row;
    }

    private TextView addStatCell(LinearLayout parent, String label, String val, String color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackgroundColor(themeColor("#161A22", "#FFFFFF"));
        cell.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.setMargins(0, 0, dp(8), 0);
        cell.setLayoutParams(cp);

        TextView tvVal = new TextView(this);
        tvVal.setText(val);
        tvVal.setTextColor(Color.parseColor(color));
        tvVal.setTextSize(22);
        tvVal.setGravity(Gravity.CENTER);

        TextView tvLbl = new TextView(this);
        tvLbl.setText(label);
        tvLbl.setTextColor(themeColor("#6B7280", "#4B5563"));
        tvLbl.setTextSize(10);
        tvLbl.setGravity(Gravity.CENTER);

        cell.addView(tvVal);
        cell.addView(tvLbl);
        parent.addView(cell);
        return tvVal;
    }

    private TextView makeSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(themeColor("#4B5563", "#9CA3AF"));
        tv.setTextSize(11);
        tv.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(8));
        tv.setLayoutParams(p);
        return tv;
    }

    private void updateSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int bgColor = themeColor("#0D0F14", "#F3F4F6");
            getWindow().setStatusBarColor(bgColor);
            getWindow().setNavigationBarColor(bgColor);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View decorView = getWindow().getDecorView();
                int flags = decorView.getSystemUiVisibility();
                if (!isDarkTheme()) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decorView.setSystemUiVisibility(flags);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View decorView = getWindow().getDecorView();
                int flags = decorView.getSystemUiVisibility();
                if (!isDarkTheme()) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decorView.setSystemUiVisibility(flags);
            }
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
