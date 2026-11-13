package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A weather glyph drawn with plain canvas geometry — the ambient screen has no icon font
 * or drawable set, and the hub only sends condition wording. The kind comes from
 * {@link AmbientData#classifyIcon(String)}; the view is square-ish and scales to its bounds.
 * Shapes are deliberately simple: a disc with rays for sun, overlapping circles over a
 * rounded base for cloud, and short strokes or dots under the cloud for rain, snow and fog.
 */
final class WeatherIconView extends View {

  private static final int SUN_COLOR = 0xffffd76a;
  private static final int CLOUD_COLOR = 0xffe9eef8;
  private static final int RAIN_COLOR = 0xff7ec8ff;
  private static final int SNOW_COLOR = 0xffffffff;
  private static final int FOG_COLOR = 0xffb9c4d6;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private AmbientData.WeatherIcon kind = AmbientData.WeatherIcon.CLOUD;

  WeatherIconView(Context context) {
    super(context);
  }

  void setKind(AmbientData.WeatherIcon icon) {
    if (icon == null || icon == kind) return;
    kind = icon;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth();
    float h = getHeight();
    if (w <= 0 || h <= 0) return;
    float u = Math.min(w, h) / 100f;
    float ox = (w - 100f * u) / 2f;
    float oy = (h - 100f * u) / 2f;
    canvas.save();
    canvas.translate(ox, oy);
    canvas.scale(u, u);
    switch (kind) {
      case SUN:
        drawSun(canvas, 50, 50, 24);
        break;
      case SUN_CLOUD:
        drawSun(canvas, 34, 32, 16);
        drawCloud(canvas, 56, 64, 66);
        break;
      case CLOUD:
        drawCloud(canvas, 50, 46, 80);
        break;
      case RAIN:
        drawCloud(canvas, 50, 34, 72);
        drawStrokes(canvas, 70, 84, RAIN_COLOR, true);
        break;
      case SNOW:
        drawCloud(canvas, 50, 34, 72);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SNOW_COLOR);
        for (float cx : new float[] {32, 50, 68}) canvas.drawCircle(cx, 76, 3.6f, paint);
        break;
      case FOG:
        drawCloud(canvas, 50, 32, 68);
        drawStrokes(canvas, 62, 86, FOG_COLOR, false);
        break;
    }
    canvas.restore();
  }

  /** Filled disc with eight rays, in the icon's 100-unit space. */
  private void drawSun(Canvas canvas, float cx, float cy, float r) {
    paint.setColor(SUN_COLOR);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(4.5f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    for (int i = 0; i < 8; i++) {
      double a = Math.PI * i / 4.0;
      float x1 = cx + (float) Math.cos(a) * r * 1.22f;
      float y1 = cy + (float) Math.sin(a) * r * 1.22f;
      float x2 = cx + (float) Math.cos(a) * r * 1.52f;
      float y2 = cy + (float) Math.sin(a) * r * 1.52f;
      canvas.drawLine(x1, y1, x2, y2, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    canvas.drawCircle(cx, cy, r, paint);
  }

  /** Cloud silhouette: three humps over a rounded base, {@code width} units wide. */
  private void drawCloud(Canvas canvas, float cx, float cy, float width) {
    float s = width / 80f;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(CLOUD_COLOR);
    canvas.drawCircle(cx - 20 * s, cy + 6 * s, 15 * s, paint);
    canvas.drawCircle(cx + 2 * s, cy - 8 * s, 21 * s, paint);
    canvas.drawCircle(cx + 24 * s, cy + 6 * s, 13 * s, paint);
    RectF base = new RectF(cx - 35 * s, cy, cx + 37 * s, cy + 21 * s);
    canvas.drawRoundRect(base, 10 * s, 10 * s, paint);
  }

  /** Three short lines under a cloud: slanted for rain, level for fog. */
  private void drawStrokes(Canvas canvas, float y1, float y2, int color, boolean slanted) {
    paint.setStyle(Paint.Style.STROKE);
    paint.setColor(color);
    paint.setStrokeWidth(4.5f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    if (slanted) {
      for (float cx : new float[] {32, 50, 68}) canvas.drawLine(cx + 3, y1, cx - 3, y2, paint);
    } else {
      float step = (y2 - y1) / 2f;
      for (int i = 0; i < 3; i++) {
        float y = y1 + step * i;
        canvas.drawLine(30, y, 70, y, paint);
      }
    }
  }
}
