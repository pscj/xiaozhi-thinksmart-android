package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Background gradients use the proportions of the upstream 1280 × 800 kiosk page. */
final class BackdropView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Shader base, left, lower, upper;
  private int leftColor = 0xff18245a;

  BackdropView(Context context) {
    super(context);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    base =
        new LinearGradient(
            0, 0, w * .7f, h, new int[] {0xff0b1128, 0xff050813}, null, Shader.TileMode.CLAMP);
    updateLeft();
    lower =
        new RadialGradient(
            0,
            0,
            1,
            new int[] {0xff0c3350, 0x000c3350},
            new float[] {0, .62f},
            Shader.TileMode.CLAMP);
    upper =
        new RadialGradient(
            0,
            0,
            1,
            new int[] {0xff1c1546, 0x001c1546},
            new float[] {0, .70f},
            Shader.TileMode.CLAMP);
  }

  void state(String state) {
    int color =
        state.equals("聆听中") || state.equals("识别中")
            ? 0xff17396d
            : state.equals("思考中")
                ? 0xff352b70
                : state.equals("回复中")
                    ? 0xff273578
                    : state.equals("连接中") ? 0xff302f58 : 0xff18245a;
    if (leftColor != color) {
      leftColor = color;
      updateLeft();
      invalidate();
    }
  }

  private void updateLeft() {
    left =
        new RadialGradient(
            0, 0, 1, new int[] {leftColor, leftColor & 0x00ffffff},
            new float[] {0, .70f}, Shader.TileMode.CLAMP);
  }

  private void ellipse(Canvas c, Shader shader, float x, float y, float rx, float ry) {
    c.save();
    c.translate(x, y);
    c.scale(rx, ry);
    paint.setShader(shader);
    c.drawCircle(0, 0, 1, paint);
    c.restore();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight();
    paint.setShader(base);
    canvas.drawRect(0, 0, w, h, paint);
    ellipse(canvas, upper, w * .78f, -h * .1f, w * 600 / 1280, h * 380 / 800);
    ellipse(canvas, lower, w, h * 1.08f, w * 820 / 1280, h * 520 / 800);
    ellipse(canvas, left, w * .2f, h * .46f, w * 760 / 1280, h * 560 / 800);
    paint.setShader(null);
  }
}
