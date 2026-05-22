package com.phishguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * InboxHealthGauge — a premium, dynamic custom canvas View that draws
 * a circular safety indicator ring with drop-shadow effects, showcasing
 * the inbox health status (e.g. 95% Safe) in real time.
 */
public class InboxHealthGauge extends View {

    private int     healthPercentage = 100;
    private final Paint trackPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint         = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval             = new RectF();

    public InboxHealthGauge(Context context) {
        super(context);
        init();
    }

    public InboxHealthGauge(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Track Paint (gray background track)
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(12));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        // Active Arc Paint
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(12));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // Center Percentage text
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));

        // Subtext "Inbox Health"
        subTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setHealth(int pct) {
        this.healthPercentage = Math.max(0, Math.min(100, pct));
        invalidate();
    }

    private boolean isDarkTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);

        float padding = dp(16);
        oval.set(padding, padding, size - padding, size - padding);

        // Dynamic Theme Colors
        boolean dark = isDarkTheme();
        int trackColor = dark ? Color.parseColor("#1F2937") : Color.parseColor("#E5E7EB");
        int centerTextColor = dark ? Color.parseColor("#FFFFFF") : Color.parseColor("#111827");
        int subTextColor = dark ? Color.parseColor("#9CA3AF") : Color.parseColor("#4B5563");

        int arcColor;
        if (healthPercentage >= 80) {
            arcColor = Color.parseColor("#22C55E"); // Safe Green
        } else if (healthPercentage >= 50) {
            arcColor = Color.parseColor("#FBBF24"); // Suspicious Amber
        } else {
            arcColor = Color.parseColor("#EF4444"); // Unsafe Red
        }

        // 1. Draw Gray Background Track
        trackPaint.setColor(trackColor);
        canvas.drawArc(oval, 135, 270, false, trackPaint);

        // 2. Draw Safety Color Arc
        arcPaint.setColor(arcColor);
        float sweepAngle = 270f * (healthPercentage / 100f);
        canvas.drawArc(oval, 135, sweepAngle, false, arcPaint);

        // 3. Draw Center Big Percentage text
        float cx = size / 2f;
        float cy = size / 2f;

        textPaint.setColor(centerTextColor);
        textPaint.setTextSize(dp(32));
        canvas.drawText(healthPercentage + "%", cx, cy + dp(6), textPaint);

        // 4. Draw Subtext underneath
        subTextPaint.setColor(subTextColor);
        subTextPaint.setTextSize(dp(11));
        canvas.drawText("INBOX HEALTH", cx, cy + dp(22), subTextPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Keep the view perfectly square for nice circle proportions
        int size = (int) dp(140);
        setMeasuredDimension(size, size);
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
