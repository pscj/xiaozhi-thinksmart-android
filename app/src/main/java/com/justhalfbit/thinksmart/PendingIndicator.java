package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Small animated waveform and thinking dots used by the conversation placeholders. */
final class PendingIndicator extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final boolean thinking;
  private final OrbView.LevelSource levels;

  PendingIndicator(Context context, boolean thinking, OrbView.LevelSource levels) {
    super(context);
    this.thinking = thinking;
    this.levels = levels;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float t = SystemClock.uptimeMillis() / 1000f;
    if (thinking) {
      paint.setColor(0xffe2a8ff);
      for (int i = 0; i < 3; i++) {
        float phase = (t - i * .15f) % 1.1f;
        float lift = phase < .33f ? (float) Math.sin(phase / .33f * Math.PI) * 6 * density : 0;
        paint.setAlpha(phase < .33f ? 255 : 115);
        canvas.drawCircle((5 + i * 13) * density, getHeight() / 2f - lift, 3.5f * density, paint);
      }
    } else {
      paint.setColor(0xff7ff3ff);
      paint.setAlpha(255);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeWidth(3.5f * density);
      float rms = levels == null ? 0 : levels.level(false);
      float level = (float) Math.max(0, Math.min(1, (20 * Math.log10(rms + .000001) + 66) / 36));
      for (int i = 0; i < 5; i++) {
        float h = (3 + level * (6 + 5 * (float) Math.sin(t * 7 + i * 1.3))) * density;
        float x = (3 + i * 6) * density;
        canvas.drawLine(x, getHeight() / 2f - h / 2, x, getHeight() / 2f + h / 2, paint);
      }
    }
    if (isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
  }
}
