package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** Native rendering of the baseline v2 orb. No timers while hidden. */
final class OrbView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String state = "待机";
  private boolean muted;
  private long stateChanged = SystemClock.uptimeMillis();

  interface LevelSource {
    float level(boolean playing);
  }

  private LevelSource levelSource;
  private float smoothedLevel;
  private final Path mouthPath = new Path();

  void setLevelSource(LevelSource source) {
    levelSource = source;
  }

  private final Shader[] glow = new Shader[5], core = new Shader[5], corona = new Shader[5];
  private final int[] lights = {0xffb4bde8, 0xff7ff3ff, 0xffe2a8ff, 0xff9cc4ff, 0xffffc56e};
  private final int[] eyePositions = {-23, 23};

  OrbView(Context context) {
    super(context);
    setContentDescription("小麦状态光球");
    int[] middles = {0xff5b65a8, 0xff1597d6, 0xff8447ef, 0xff4d6bff, 0xffd0741f};
    int[] darks = {0xff191d45, 0xff062f5c, 0xff250e66, 0xff170b5c, 0xff4a2208};
    for (int i = 0; i < 5; i++) {
      glow[i] =
          new RadialGradient(
              0,
              0,
              165,
              new int[] {(middles[i] & 0xffffff) | 0x65000000, 0},
              null,
              Shader.TileMode.CLAMP);
      core[i] =
          new RadialGradient(
              -33,
              -42,
              170,
              new int[] {0xfff2f5ff, lights[i], middles[i], darks[i]},
              new float[] {0, .22f, .60f, 1},
              Shader.TileMode.CLAMP);
      corona[i] =
          new RadialGradient(
              0, 0, 164, new int[] {middles[i], middles[i], lights[i]},
              new float[] {0, 110f / 164f, 1}, Shader.TileMode.CLAMP);
    }
  }

  void state(String value, boolean muted) {
    if (!this.state.equals(value)) stateChanged = SystemClock.uptimeMillis();
    this.state = value;
    this.muted = muted;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float scale = Math.min(getWidth(), getHeight()) / 330f;
    canvas.save();
    canvas.translate(getWidth() / 2f, getHeight() / 2f);
    canvas.scale(scale, scale);
    float t = SystemClock.uptimeMillis() / 1000f;
    float phase = t * 2.4f;
    boolean listening = state.equals("聆听中") || state.equals("识别中"), thinking = state.equals("思考中");
    boolean speaking = state.equals("回复中"), connecting = state.equals("连接中");
    int theme = listening ? 1 : thinking ? 2 : speaking ? 3 : connecting ? 4 : 0;
    int light = lights[theme];
    paint.setShader(glow[theme]);
    canvas.drawCircle(0, 0, 165, paint);
    paint.setShader(null);
    if (thinking) canvas.scale(1.1f, 1.1f);
    paint.setStrokeWidth(6f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    float rms = levelSource == null ? 0 : levelSource.level(speaking);
    float target =
        speaking
            ? Math.min(1, rms * 6)
            : (float) Math.max(0, Math.min(1, (20 * Math.log10(rms + .000001) + 66) / 36));
    smoothedLevel += (target - smoothedLevel) * (target > smoothedLevel ? .55f : .2f);
    float level = smoothedLevel;
    canvas.save();
    if (listening || speaking) canvas.scale(1 + level * .16f, 1 + level * .16f);
    paint.setShader(corona[theme]);
    for (int i = 0; i < 48; i++) {
      double angle = i * Math.PI / 24 + (thinking ? t * Math.PI * 2 / 1.5 : connecting ? t * Math.PI * 2 / 3 : 0);
      float length = 8, opacity = .4f;
      if (thinking) {
        float f = i / 48f;
        length = 6.6f + 28.5f * f * f;
        opacity = .1f + .9f * (float) Math.pow(f, 1.6);
      } else if (connecting) {
        length = i % 4 == 0 ? 16.5f : 4.2f;
        opacity = i % 4 == 0 ? 1 : .35f;
      } else if (listening) {
        float elapsed = (SystemClock.uptimeMillis() - stateChanged) / 1000f;
        float burst = Math.max(0, 1 - elapsed / .6f);
        float envelope = Math.max(level, .5f * burst * burst);
        float grain = (float) (.84 + .16 * Math.sin(angle * 5 + phase * 3.1) * Math.sin(angle * 3 - phase * 2.3));
        length = (float) (7 + 1.2 * Math.sin(angle * 4 + phase * 1.5) + 46 * envelope * grain);
        opacity = 1;
      } else if (speaking) {
        float lobe = (float) (.5 + .5 * Math.sin(angle * 3 - phase * 1.3));
        length = 8 + 4 * lobe + 46 * level * (.3f + .7f * lobe);
        opacity = .6f + .4f * lobe;
      }
      paint.setColor(theme == 0 ? 0xff5a66a8 : light);
      paint.setAlpha((int) (255 * opacity));
      if (length <= 6) {
        canvas.drawCircle(
            (float) Math.cos(angle) * 112, (float) Math.sin(angle) * 112, length / 2, paint);
      } else {
        canvas.drawLine(
            (float) Math.cos(angle) * 113,
            (float) Math.sin(angle) * 113,
            (float) Math.cos(angle) * (107 + length),
            (float) Math.sin(angle) * (107 + length),
            paint);
      }
    }
    canvas.restore();
    paint.setShader(null);
    paint.setAlpha(255);
    float coreScale = (listening || speaking) ? 1 + level * .13f : 1;
    canvas.scale(
        coreScale * (1 + .025f * (float) Math.sin(t * (thinking ? 3.4 : 1.4))),
        coreScale * (1 + .025f * (float) Math.sin(t * 1.4)));
    paint.setShader(core[theme]);
    canvas.drawCircle(0, 0, 95, paint);
    paint.setShader(null);
    float look =
        connecting ? (float) Math.sin(t * 3) * 9 : thinking ? -9 : (float) Math.sin(t * .6) * 3;
    canvas.save();
    canvas.translate(look, thinking ? -9 : 0);
    boolean blink = muted || (!thinking && !listening && (t % 5.2f) > 4.95f);
    float eyeHeight = blink ? 3 : thinking ? 10 : listening ? 20 : 16;
    paint.setColor(0xff0e1638);
    for (int x : eyePositions) {
      canvas.drawRoundRect(x - 12, -4 - eyeHeight, x + 12, -4 + eyeHeight, 12, 12, paint);
      if (!blink) {
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x - 3, -11, 3.8f, paint);
        paint.setColor(0xff0e1638);
      }
    }
    if (thinking) canvas.drawRoundRect(-7, 21, 7, 25, 2, 2, paint);
    else if (listening) canvas.drawCircle(0, 27, 7, paint);
    else {
      float height = speaking ? 11 + 10 * level : 11;
      mouthPath.reset();
      mouthPath.moveTo(-11, 21);
      mouthPath.lineTo(11, 21);
      mouthPath.quadTo(11, 21 + height, 0, 21 + height);
      mouthPath.quadTo(-11, 21 + height, -11, 21);
      mouthPath.close();
      canvas.drawPath(mouthPath, paint);
    }
    paint.setColor(0x65ff8fb8);
    canvas.drawOval(-54, 15, -32, 27, paint);
    canvas.drawOval(32, 15, 54, 27, paint);
    canvas.restore();
    canvas.restore();
    if (isShown() && getWindowVisibility() == VISIBLE)
      postInvalidateDelayed(state.equals("待机") ? 100 : 33);
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    if (visibility == VISIBLE) invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility == VISIBLE) invalidate();
  }
}
