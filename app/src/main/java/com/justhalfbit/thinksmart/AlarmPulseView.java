package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Thin warm-white breathing rings around the alarm time, as drawn in the
 * dawn ringing design: two hairline circles whose radius swells slightly
 * and whose alpha fades as they breathe. Follows the PendingIndicator
 * pattern: the phase comes from the clock in onDraw and the view
 * re-invalidates itself only while actually shown, so the animation needs
 * no extra thread or handler and stops when the alarm layer hides.
 */
final class AlarmPulseView extends View {
  private static final int RING = 0xffffe2be;
  private static final long PERIOD_MS = 6400;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  AlarmPulseView(Context context) {
    super(context);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float cx = getWidth() / 2f, cy = getHeight() / 2f;
    float base = Math.min(getWidth(), getHeight()) / 2f;
    // 0..1 breathing phase: slow swell and fade from the clock.
    double angle = 2 * Math.PI * (SystemClock.uptimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
    float breath = (float) (0.5 - 0.5 * Math.cos(angle));
    float swell = 1f + 0.035f * breath;
    paint.setStyle(Paint.Style.STROKE);
    paint.setColor(RING);
    // Outer hairline ring (the design's larger ring): faintest, fades most.
    paint.setStrokeWidth(1 * density);
    paint.setAlpha(Math.round(51 * (0.8f - 0.45f * breath)));
    canvas.drawCircle(cx, cy, base * 0.99f * swell, paint);
    // Inner ring: a soft warm glow pass under a brighter hairline.
    paint.setStrokeWidth(4 * density);
    paint.setAlpha(Math.round(49 * (1f - 0.4f * breath)));
    canvas.drawCircle(cx, cy, base * 0.77f * swell, paint);
    paint.setStrokeWidth(1.5f * density);
    paint.setAlpha(Math.round(140 * (1f - 0.4f * breath)));
    canvas.drawCircle(cx, cy, base * 0.77f * swell, paint);
    if (isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
  }
}
