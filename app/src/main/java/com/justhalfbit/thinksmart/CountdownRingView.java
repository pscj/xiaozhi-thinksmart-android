package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Progress ring for the full-screen countdown, in a 310dp carrier of
 * its own (smaller than the ringing screen's pulse view, so the ring
 * clears the countdown screen's bottom stack): a faint warm-white full
 * circle is the elapsed ghost,
 * and a bright warm-white arc on top is the time remaining. The arc
 * always ends at twelve o'clock and its start chases clockwise as time
 * passes, so the ring visibly depletes clockwise from the top. When the
 * timer is paused the whole ring drops to pale grey-white and the glow
 * disappears. The fraction is pushed in once per second by the activity;
 * the view itself owns no thread or animation.
 */
final class CountdownRingView extends View {
  private static final int TRACK = 0xffe2be, ARC = 0xfffff3da, HELD = 0xffe2e4f0;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF oval = new RectF();
  private float fraction = 1f;
  private boolean paused;

  CountdownRingView(Context context) {
    super(context);
  }

  /** Remaining share of the total duration, 0..1; paused greys the ring out. */
  void setProgress(float remainingFraction, boolean isPaused) {
    fraction = Math.max(0f, Math.min(1f, remainingFraction));
    paused = isPaused;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float cx = getWidth() / 2f, cy = getHeight() / 2f;
    float radius = Math.min(getWidth(), getHeight()) / 2f * 0.99f;
    oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    // Elapsed ghost: the full circle, faintest warm white (paler when paused).
    paint.setColor(paused ? HELD : TRACK);
    paint.setAlpha(paused ? 36 : 41);
    paint.setStrokeWidth(1.5f * density);
    canvas.drawCircle(cx, cy, radius, paint);
    if (fraction <= 0f) return;
    float start = -90f + (1f - fraction) * 360f, sweep = fraction * 360f;
    if (!paused) {
      // Warm glow pass under the bright arc.
      paint.setColor(ARC);
      paint.setAlpha(70);
      paint.setStrokeWidth(4.5f * density);
      canvas.drawArc(oval, start, sweep, false, paint);
    }
    paint.setColor(paused ? HELD : ARC);
    paint.setAlpha(paused ? 107 : 242);
    paint.setStrokeWidth(2f * density);
    canvas.drawArc(oval, start, sweep, false, paint);
  }
}
