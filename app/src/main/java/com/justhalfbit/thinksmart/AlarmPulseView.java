package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Breathing accent rings shown while an alarm rings. Follows the
 * PendingIndicator pattern: the phase comes from the clock in onDraw and the
 * view re-invalidates itself only while actually shown, so the animation
 * needs no extra thread or handler and stops when the alarm layer hides.
 */
final class AlarmPulseView extends View {
  private static final int ACCENT = 0xffffd68a;
  private static final long PERIOD_MS = 1800;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  AlarmPulseView(Context context) {
    super(context);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float cx = getWidth() / 2f, cy = getHeight() / 2f;
    float base = Math.min(getWidth(), getHeight()) / 2f - 4 * density;
    // 0..1 breathing phase: slow swell and fade from the clock.
    double angle = 2 * Math.PI * (SystemClock.uptimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
    float breath = (float) (0.5 - 0.5 * Math.cos(angle));
    // Soft halo that swells with the breath.
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(ACCENT);
    paint.setAlpha(26 + Math.round(30 * breath));
    canvas.drawCircle(cx, cy, base * (0.86f + 0.14f * breath), paint);
    // Ring that brightens as it swells.
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(3 * density);
    paint.setAlpha(120 + Math.round(135 * breath));
    canvas.drawCircle(cx, cy, base * (0.68f + 0.08f * breath), paint);
    // Steady core dot.
    paint.setStyle(Paint.Style.FILL);
    paint.setAlpha(255);
    canvas.drawCircle(cx, cy, 5 * density, paint);
    if (isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
  }
}
