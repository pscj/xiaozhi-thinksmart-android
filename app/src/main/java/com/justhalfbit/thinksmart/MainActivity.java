package com.justhalfbit.thinksmart;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity implements VoiceService.Listener {
  private VoiceService service;
  private boolean bound,
      binding,
      visible,
      black,
      blackForSleep,
      active,
      restoringTouch,
      microphoneHeld,
      overlayOpen,
      navigationVisible;
  private Runnable micHold;
  private VoiceService.Snapshot snapshot;
  private SharedPreferences prefs;
  private Dialog settingsDialog;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private TextView status,
      sleepChip,
      title,
      hint,
      clock,
      welcomeTime,
      welcomeDate,
      warning,
      conversationTitle;
  private TextView welcomeGreeting;
  private LinearLayout welcome, inputRow;
  private LinearLayout model;
  private TextView modelText, modelDetail;
  private ImageView modelIcon, conversationIcon;
  private LinearLayout conversationHead;
  private Button reports, abortButton, endButton;
  private LinearLayout messages;
  private ScrollView scroll;
  private FrameLayout frame;
  private View curtain, micRing;
  private View volumeHud;
  private Runnable hideVolumeHud;
  private OrbView orb;
  private BackdropView backdrop;
  private AmbientView ambient;
  private EditText input;
  private static final long AMBIENT_IDLE_MS = 60_000L;
  private static final long BLACK_RECHECK_MS = 30_000L;
  private static final String AMBIENT_MODE_PREF = "ambientMode";
  private String rendered = "";
  private Boolean lastConversationStyle, lastAbortStyle;
  private int lastModelLogo = -1;
  private long lastInteraction, lastWake;
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = ((VoiceService.LocalBinder) binder).service();
          bound = true;
          if (visible) service.attach(MainActivity.this);
        }

        public void onServiceDisconnected(ComponentName name) {
          bound = false;
          service = null;
          showText(status, "服务已停止");
        }
      };
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!visible) return;
          updateClock();
          applyBrightness();
          long now = SystemClock.elapsedRealtime();
          // While the ambient screen is up it counts as the screen being
          // intentionally on: the idle black-out waits until it goes away.
          // Exception: inside the sleep window the ambient screen no longer
          // holds the black-out off, so the display can still blank overnight.
          boolean ambientUp = ambient != null && ambient.isShowing();
          boolean sleeping = snapshot != null && snapshot.sleeping;
          if (Protocol.idleExpired(
              now,
              lastInteraction,
              prefs.getBoolean("autoOff", true) ? prefs.getInt("offMinutes", 5) : 0,
              active || overlayOpen || (ambientUp && !sleeping))) {
            // Remember whether this black-out belongs to the sleep window: only
            // that kind is lifted automatically once the window ends. A daytime
            // idle black-out still waits for a touch, as before.
            blackForSleep = sleeping;
            setBlack(true);
          }
          updateAmbient(now);
          ui.postDelayed(this, 1000);
        }
      };
  private final Runnable blackRecheck =
      new Runnable() {
        public void run() {
          if (!black || !blackForSleep) return;
          if (!inSleepWindowNow()) {
            restoreFromBlack();
            return;
          }
          ui.postDelayed(this, BLACK_RECHECK_MS);
        }
      };

  /** The same window test VoiceService.sleeping() applies, from local prefs. */
  private boolean inSleepWindowNow() {
    Calendar c = Calendar.getInstance();
    return prefs.getBoolean("sleep", false)
        && Protocol.inSleep(
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            Protocol.minutes(prefs.getString("sleepStart", "22:00")),
            Protocol.minutes(prefs.getString("sleepEnd", "07:00")));
  }

  /** The sleep window has ended: lift the curtain and return to the standby screen. */
  private void restoreFromBlack() {
    blackForSleep = false;
    if (black) setBlack(false);
    // Backdate the idle clock so the standby screen returns at once instead of
    // after another idle minute.
    lastInteraction = SystemClock.elapsedRealtime() - AMBIENT_IDLE_MS;
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    if (pm != null && !pm.isInteractive()) {
      PowerManager.WakeLock screen =
          pm.newWakeLock(
              PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
              "thinksmart:sleep-restore");
      screen.acquire(3000);
    }
    if (visible) updateAmbient(SystemClock.elapsedRealtime());
  }

  /**
   * After 60 s without a conversation or touch, show only the selected standby mode.
   * Anything that counts as interaction (touch() moving
   * lastInteraction, a conversation going active, an overlay, the black curtain) hides it.
   */
  private void updateAmbient(long now) {
    if (ambient == null) return;
    long idleMs = now - lastInteraction;
    if (!active && !overlayOpen && !black && idleMs >= AMBIENT_IDLE_MS) {
      if (!ambient.isShowing()) {
        ambient.show(prefs.getBoolean(AMBIENT_MODE_PREF, false)
            ? AmbientView.Mode.PHOTO : AmbientView.Mode.INFO);
      }
    } else if (ambient.isShowing()) {
      ambient.hide();
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("config", MODE_PRIVATE);
    navigationVisible = false;
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    applyNavigationVisibility(getWindow());
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else
      getWindow()
          .addFlags(
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                  | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    lastInteraction = SystemClock.elapsedRealtime();
    buildUi("");
  }

  private void applyNavigationVisibility(Window window) {
    window.getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN
            | (navigationVisible ? 0
                : View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION));
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private void showText(TextView view, String text) {
    if (!view.getText().toString().equals(text)) view.setText(text);
    if (view == warning) view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private GradientDrawable card(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    d.setStroke(dp(1), 0x18ffffff);
    return d;
  }

  private GradientDrawable selectedChoice() {
    GradientDrawable d =
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xff8ff0ff, 0xff8fa6ff});
    d.setCornerRadius(dp(16));
    return d;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(14);
    b.setTextColor(0xfff2f5ff);
    b.setAllCaps(false);
    b.setBackground(card(0x16ffffff, 16));
    b.setMinHeight(dp(48));
    b.setPadding(dp(14), dp(4), dp(14), dp(4));
    b.setOnClickListener(
        v -> {
          touch();
          action.run();
        });
    return b;
  }

  private Button controlButton(String icon, String caption, Runnable action) {
    Button result = button("", action);
    android.text.SpannableString content = new android.text.SpannableString(icon + "\n" + caption);
    if (icon.equals("■"))
      content.setSpan(new android.text.style.RelativeSizeSpan(.65f), 0, icon.length(), 0);
    content.setSpan(
        new android.text.style.RelativeSizeSpan(.72f), icon.length() + 1, content.length(), 0);
    result.setText(content);
    result.setGravity(Gravity.CENTER);
    return result;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private void buildUi(String draft) {
    if (hideVolumeHud != null) ui.removeCallbacks(hideVolumeHud);
    volumeHud = null;
    lastConversationStyle = null;
    lastAbortStyle = null;
    lastModelLogo = -1;
    boolean landscape =
        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    frame = new FrameLayout(this);
    backdrop = new BackdropView(this);
    frame.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    setContentView(frame);
    LinearLayout root = column();
    root.setPadding(dp(24), dp(14), dp(24), dp(20));
    root.setClipToPadding(false);
    root.setClipChildren(false);
    frame.addView(root);
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    if (landscape) top.setTranslationY(-dp(8));
    LinearLayout brand = new LinearLayout(this);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setContentDescription(navigationVisible ? "隐藏系统导航栏" : "显示系统导航栏");
    brand.setOnClickListener(
        v -> {
          touch();
          navigationVisible = !navigationVisible;
          applyNavigationVisibility(getWindow());
          brand.setContentDescription(navigationVisible ? "隐藏系统导航栏" : "显示系统导航栏");
        });
    ImageView brandIcon = new ImageView(this);
    GradientDrawable brandBackground =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff6c8cff, 0xff5ee0ff});
    brandBackground.setCornerRadius(dp(10));
    brandIcon.setBackground(brandBackground);
    brandIcon.setPadding(dp(7), dp(7), dp(7), dp(7));
    brandIcon.setImageResource(R.drawable.ic_brand_wave);
    brand.addView(brandIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
    TextView brandName = label("小麦", 18, 0xfff2f5ff);
    brandName.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams brandNameParams = new LinearLayout.LayoutParams(-2, -2);
    brandNameParams.leftMargin = dp(8);
    brand.addView(brandName, brandNameParams);
    top.addView(brand, new LinearLayout.LayoutParams(-2, dp(50)));
    model = new LinearLayout(this);
    model.setGravity(Gravity.CENTER_VERTICAL);
    model.setPadding(dp(6), 0, dp(10), 0);
    model.setBackground(card(0x0bffffff, 16));
    model.setDefaultFocusHighlightEnabled(false);
    model.setOnClickListener(
        v -> {
          touch();
          models();
        });
    model.setContentDescription("切换模型");
    modelIcon = new ImageView(this);
    modelIcon.setPadding(dp(5), dp(5), dp(5), dp(5));
    modelIcon.setBackground(card(0xffffffff, 10));
    modelIcon.setVisibility(View.GONE);
    model.addView(modelIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
    LinearLayout modelLabels = column();
    modelLabels.setPadding(dp(8), 0, 0, 0);
    modelText = label("选择模型", 13, 0xfff2f5ff);
    modelDetail = label("", 9, 0xff7883ab);
    modelDetail.setTypeface(Typeface.MONOSPACE);
    modelDetail.setSingleLine(true);
    modelDetail.setVisibility(View.GONE);
    modelLabels.addView(modelText);
    modelLabels.addView(modelDetail);
    model.addView(modelLabels, new LinearLayout.LayoutParams(0, -2, 1));
    model.addView(label("⌄", 16, 0xff7883ab));
    LinearLayout.LayoutParams pill = new LinearLayout.LayoutParams(dp(170), dp(44));
    pill.setMargins(dp(12), 0, dp(12), 0);
    if (!landscape) pill.width = dp(125);
    top.addView(model, pill);
    top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    reports = button("▶ 待汇报", () -> withService(() -> service.playReports()));
    reports.setVisibility(View.GONE);
    top.addView(reports);
    reports.setTextColor(0xff2a1800);
    GradientDrawable reportBackground =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xffffd68a, 0xffffb547});
    reportBackground.setCornerRadius(dp(24));
    reports.setBackground(reportBackground);
    sleepChip = label("☾ 睡眠中", 13, 0xffd9d2ff);
    sleepChip.setPadding(dp(10), dp(5), dp(10), dp(5));
    sleepChip.setBackground(card(0x189b8cff, 20));
    sleepChip.setVisibility(View.GONE);
    top.addView(sleepChip);
    status = label("● 待机", 14, 0xffb6c0e2);
    status.setPadding(dp(10), dp(5), dp(10), dp(5));
    status.setBackground(card(0x0bffffff, 20));
    top.addView(status);
    clock = label("", 20, 0xfff2f5ff);
    clock.setPadding(dp(10), 0, dp(10), 0);
    clock.setVisibility(View.GONE);
    if (landscape) top.addView(clock);
    android.widget.ImageButton gear = new android.widget.ImageButton(this);
    gear.setContentDescription("设置");
    gear.setPadding(dp(7), dp(7), dp(7), dp(7));
    gear.setBackground(card(0x0bffffff, 12));
    gear.setImageResource(R.drawable.ic_sliders);
    gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
    gear.setOnClickListener(v -> { touch(); settings(); });
    top.addView(gear, new LinearLayout.LayoutParams(dp(35), dp(35)));
    root.addView(top);
    if (!landscape) {
      top.removeView(model);
      top.removeView(reports);
      top.removeView(status);
      top.removeView(sleepChip);
      LinearLayout second = new LinearLayout(this);
      second.setGravity(Gravity.CENTER_VERTICAL);
      second.addView(model, new LinearLayout.LayoutParams(0, dp(42), 1));
      second.addView(reports);
      second.addView(sleepChip);
      second.addView(status);
      root.addView(second);
    }
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout stage = column();
    stage.setGravity(Gravity.CENTER);
    stage.setClipChildren(false);
    if (landscape) stage.setPadding(0, 0, 0, dp(20));
    orb = new OrbView(this);
    if (landscape) orb.setTranslationY(dp(8));
    orb.setLevelSource(playing -> service == null ? 0 : service.audioLevel(playing));
    stage.addView(orb, new LinearLayout.LayoutParams(landscape ? dp(220) : dp(170), 0, 1));
    title = label("", 23, 0xfff2f5ff);
    android.text.SpannableString initialTitle = new android.text.SpannableString("说「" + displayedWakePhrase() + "」");
    initialTitle.setSpan(
        new android.text.style.ForegroundColorSpan(0xff9edaff), 1, initialTitle.length(), 0);
    title.setText(initialTitle);
    title.setGravity(Gravity.CENTER);
    title.setTextScaleX(1.10f);
    title.setLetterSpacing(.02f);
    title.setTypeface(null, Typeface.BOLD);
    stage.addView(title);
    hint = label("或者点麦克风；按住可以直接说", 13, 0xffb6c0e2);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(0, dp(8), 0, dp(16));
    stage.addView(hint);
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER);
    controls.setClipChildren(false);
    controls.setClipToPadding(false);
    if (landscape) controls.setTranslationY(-dp(5));
    abortButton = controlButton("Ⅱ", "打断", () -> withService(() -> service.abort()));
    abortButton.setEnabled(false);
    abortButton.setAlpha(.22f);
    controls.addView(abortButton, new LinearLayout.LayoutParams(dp(64), dp(54)));
    Button mic = button("", () -> withService(() -> service.wake()));
    GradientDrawable micBackground =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff8ff0ff, 0xff6c8cff});
    micBackground.setShape(GradientDrawable.OVAL);
    mic.setBackground(micBackground);
    mic.setGravity(Gravity.CENTER);
    mic.setContentDescription("点击唤醒，按住说话");
    FrameLayout micFrame = new FrameLayout(this);
    micFrame.setClipChildren(false);
    micFrame.setClipToPadding(false);
    View halo = new View(this);
    GradientDrawable haloBg = new GradientDrawable();
    haloBg.setShape(GradientDrawable.OVAL);
    haloBg.setColor(0x0cffffff);
    halo.setBackground(haloBg);
    micFrame.addView(halo, new FrameLayout.LayoutParams(dp(90), dp(90), Gravity.CENTER));
    micRing = new View(this);
    GradientDrawable ringBg = new GradientDrawable();
    ringBg.setShape(GradientDrawable.OVAL);
    ringBg.setColor(Color.TRANSPARENT);
    ringBg.setStroke(dp(2), 0xbf7ff3ff);
    micRing.setBackground(ringBg);
    micRing.setVisibility(View.GONE);
    micFrame.addView(micRing, new FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER));
    micFrame.addView(mic, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));
    ImageView micIcon = new ImageView(this);
    micIcon.setImageResource(R.drawable.ic_microphone);
    micIcon.setElevation(dp(8));
    micIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    micFrame.addView(micIcon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(96), dp(96));
    mp.setMargins(dp(13), 0, dp(13), 0);
    controls.addView(micFrame, mp);
    micHold =
        () -> {
          microphoneHeld = true;
          withService(() -> service.ptt(true));
        };
    mic.setOnTouchListener(
        (v, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            microphoneHeld = false;
            mic.animate().scaleX(.93f).scaleY(.93f).setDuration(140).start();
            micIcon.animate().scaleX(.93f).scaleY(.93f).setDuration(140).start();
            ui.postDelayed(micHold, 350);
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP
              || e.getAction() == MotionEvent.ACTION_CANCEL) {
            mic.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            micIcon.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            ui.removeCallbacks(micHold);
            if (microphoneHeld) {
              if (service != null) service.ptt(false);
            } else if (e.getAction() == MotionEvent.ACTION_UP) v.performClick();
            microphoneHeld = false;
            return true;
          }
          return true;
        });
    endButton =
        controlButton(
            "■",
            "结束",
            () -> {
              cancelHold();
              if (service != null) {
                service.end();
                Toast.makeText(this, "对话已结束，聊天记录已保存", Toast.LENGTH_SHORT).show();
              }
            });
    endButton.setEnabled(false);
    endButton.setTextColor(0xffb6c0e2);
    endButton.setBackground(card(0x0bffffff, 16));
    endButton.setAlpha(.22f);
    controls.addView(endButton, new LinearLayout.LayoutParams(dp(64), dp(54)));
    stage.addView(controls);
    Button preview = button("试听最近录音", () -> withService(() -> {
      if (active) {
        Toast.makeText(this, "请先结束当前对话再试听", Toast.LENGTH_SHORT).show();
      } else if (service.previewRecentAudio()) {
        Toast.makeText(this, "正在播放最近 10 秒录音", Toast.LENGTH_SHORT).show();
      } else {
        Toast.makeText(this, "暂无录音或正在试听", Toast.LENGTH_SHORT).show();
      }
    }));
    stage.addView(preview, new LinearLayout.LayoutParams(dp(150), dp(42)));
    body.addView(
        stage,
        landscape
            ? new LinearLayout.LayoutParams(0, -1, .75f)
            : new LinearLayout.LayoutParams(-1, 0, 1.1f));
    LinearLayout side = column();
    side.setPadding(dp(24), 0, 0, 0);
    body.addView(
        side,
        landscape
            ? new LinearLayout.LayoutParams(0, -1, 1.25f)
            : new LinearLayout.LayoutParams(-1, 0, 1));
    welcome = column();
    welcome.setGravity(Gravity.CENTER_VERTICAL);
    welcome.setPadding(dp(4), 0, 0, dp(8));
    welcomeGreeting = label("你好", 19, 0xffb6c0e2);
    if (landscape) welcomeGreeting.setTranslationY(dp(4));
    welcome.addView(welcomeGreeting);
    welcomeTime = label("", landscape ? 112 : 38, 0xffe3e7ff);
    welcomeTime.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
    if (landscape) {
      welcomeTime.setTranslationY(-dp(20));
      welcomeTime.setPivotX(0);
      welcomeTime.setScaleX(.88f);
      welcomeTime.post(
          () -> {
            welcomeTime
                .getPaint()
                .setShader(
                    new android.graphics.LinearGradient(
                        0,
                        0,
                        0,
                        welcomeTime.getHeight(),
                        new int[] {0xffffffff, 0xffa8b8ff},
                        null,
                        android.graphics.Shader.TileMode.CLAMP));
            welcomeTime.invalidate();
          });
    }
    welcome.addView(welcomeTime);
    welcomeDate = label("", 17, 0xffb6c0e2);
    if (landscape) welcomeDate.setTranslationY(-dp(22));
    welcome.addView(welcomeDate);
    TextView tryIt = label("试试这样问我", 13, 0xff7883ab);
    tryIt.setPadding(0, dp(28), 0, dp(10));
    if (landscape) tryIt.setTranslationY(-dp(15));
    if (landscape) welcome.addView(tryIt);
    String[] suggestions = {"明天北京天气怎么样？", "帮我想个周末计划", "讲个冷笑话", "把音量调小一点"};
    int[] symbols = {
      R.drawable.ic_suggestion_weather,
      R.drawable.ic_suggestion_plan,
      R.drawable.ic_suggestion_joke,
      R.drawable.ic_suggestion_volume
    };
    int[] iconBg = {0x29ffb547, 0x245ee0ff, 0x24ff7ac8, 0x2b9b7bff};
    for (int row = 0; row < 2; row++) {
      LinearLayout chips = new LinearLayout(this);
      if (landscape) chips.setTranslationY(-dp(15));
      for (int i = row * 2; i < row * 2 + 2; i++) {
        final String question = suggestions[i];
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(4), dp(16), dp(4));
        chip.setBackground(card(0x0bffffff, 16));
        chip.setOnClickListener(v -> withService(() -> service.text(question)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(symbols[i]);
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        icon.setBackground(card(iconBg[i], 10));
        chip.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = label(question, 14, 0xfff2f5ff);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.leftMargin = dp(8);
        chip.addView(name, nameParams);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(44));
        cp.setMargins(0, 0, dp(8), dp(8));
        chips.addView(chip, cp);
      }
      if (landscape) welcome.addView(chips);
    }
    side.addView(welcome, new LinearLayout.LayoutParams(-1, 0, 1));
    conversationHead = new LinearLayout(this);
    conversationHead.setGravity(Gravity.CENTER_VERTICAL);
    conversationHead.setPadding(dp(4), 0, 0, dp(10));
    conversationHead.setVisibility(View.GONE);
    conversationIcon = new ImageView(this);
    conversationIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
    conversationIcon.setBackground(card(0xffffffff, 8));
    LinearLayout.LayoutParams conversationIconParams =
        new LinearLayout.LayoutParams(dp(24), dp(24));
    conversationIconParams.rightMargin = dp(8);
    conversationHead.addView(conversationIcon, conversationIconParams);
    conversationTitle = label("对话", 13, 0xffb6c0e2);
    conversationHead.addView(conversationTitle);
    side.addView(conversationHead);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVisibility(View.GONE);
    messages = column();
    messages.setGravity(Gravity.BOTTOM);
    messages.setPadding(dp(4), dp(8), dp(4), dp(12));
    scroll.addView(messages);
    side.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout bottom = new LinearLayout(this);
    inputRow = bottom;
    bottom.setVisibility(draft.isEmpty() ? View.GONE : View.VISIBLE);
    bottom.setGravity(Gravity.CENTER_VERTICAL);
    input = new EditText(this);
    input.setTextColor(0xfff2f5ff);
    input.setTextSize(16);
    input.setHintTextColor(0xff7883ab);
    input.setHint("输入文字问题");
    input.setSingleLine(true);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setText(draft);
    bottom.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
    bottom.addView(
        button(
            "发送 ↑",
            () -> {
              String text = input.getText().toString().trim();
              if (!text.isEmpty())
                withService(
                    () -> {
                      service.text(text);
                      input.setText("");
                    });
            }));
    root.addView(bottom);
    warning = label("", 13, 0xffffd6dd);
    warning.setMaxLines(2);
    warning.setVisibility(View.GONE);
    warning.setMaxWidth(dp(506));
    warning.setPadding(dp(18), dp(10), dp(18), dp(10));
    warning.setBackground(card(0xff182046, 16));
    FrameLayout.LayoutParams warningParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    warningParams.topMargin = dp(70);
    frame.addView(warning, warningParams);
    ambient = new AmbientView(this);
    java.io.File externalFiles = getExternalFilesDir(null);
    java.io.File photosDir =
        new java.io.File(externalFiles != null ? externalFiles : getFilesDir(), "photos");
    ambient.bind(
        new AmbientRepository(this, () -> prefs.getString("http", "")),
        photosDir,
        this::displayedWakePhrase);
    // Below the black curtain: the curtain still wins when the screen blanks.
    frame.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
    curtain = new View(this);
    curtain.setBackgroundColor(Color.BLACK);
    curtain.setContentDescription("触摸恢复屏幕");
    curtain.setOnClickListener(v -> touch());
    frame.addView(curtain, new FrameLayout.LayoutParams(-1, -1));
    curtain.setVisibility(View.GONE);
    black = false;
    rendered = "";
    updateClock();
    if (snapshot != null) update(snapshot);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) applyNavigationVisibility(getWindow());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("voiceWake", false)) touch();
    if (intent.getBooleanExtra("sleepRestore", false)) restoreFromBlack();
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    cancelHold();
    super.onConfigurationChanged(config);
    buildUi(input.getText().toString());
    applyBrightness();
  }

  private void updateClock() {
    Date now = new Date();
    String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(now);
    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    showText(
        welcomeGreeting,
        hour < 5
            ? "夜深了"
            : hour < 9
                ? "早上好"
                : hour < 12
                    ? "上午好"
                    : hour < 14 ? "中午好" : hour < 18 ? "下午好" : hour < 23 ? "晚上好" : "夜深了");
    showText(clock, time);
    showText(welcomeTime, time);
    showText(welcomeDate, new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
  }

  @Override
  public void showVolume(int volume) {
    if (frame == null) return;
    if (hideVolumeHud != null) ui.removeCallbacks(hideVolumeHud);
    if (volumeHud != null) frame.removeView(volumeHud);
    int value = Math.max(0, Math.min(100, volume));
    LinearLayout hud = new LinearLayout(this);
    hud.setGravity(Gravity.CENTER_VERTICAL);
    hud.setPadding(dp(20), dp(17), dp(20), dp(17));
    GradientDrawable background = card(0xff141b3c, 19);
    background.setStroke(dp(1), 0x26ffffff);
    hud.setBackground(background);
    hud.setElevation(dp(16));
    hud.setContentDescription("音量 " + value);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_volume_hud);
    hud.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
    android.widget.ProgressBar bar =
        new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setProgress(value);
    bar.setProgressDrawable(getDrawable(R.drawable.brightness_track));
    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(8), 1);
    barParams.setMargins(dp(13), 0, dp(13), 0);
    hud.addView(bar, barParams);
    TextView number = label(Integer.toString(value), 23, 0xfff2f5ff);
    number.setTypeface(null, Typeface.BOLD);
    number.setGravity(Gravity.END);
    hud.addView(number, new LinearLayout.LayoutParams(dp(43), -2));
    int width = Math.min(dp(294), getResources().getDisplayMetrics().widthPixels - dp(32));
    frame.addView(hud, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
    volumeHud = hud;
    hideVolumeHud = () -> {
      if (volumeHud == hud) {
        frame.removeView(hud);
        volumeHud = null;
      }
    };
    ui.postDelayed(hideVolumeHud, 1600);
  }

  private String displayedWakePhrase() {
    String phrase = prefs.getString(WakePhrase.KEY, "");
    return phrase == null || phrase.isEmpty() ? "小麦小麦" : phrase;
  }

  public void update(VoiceService.Snapshot value) {
    snapshot = value;
    if (value.state.equals("服务已停止")) {
      stopVoice();
      return;
    }
    boolean wasActive = active;
    boolean conversation = Protocol.conversationActive(value.state);
    // Background reconnects must not count as user activity or wake the screen.
    active = conversation;
    if (wasActive && !active) lastInteraction = SystemClock.elapsedRealtime();
    if (active || value.wakeSerial != lastWake) {
      touch();
      lastWake = value.wakeSerial;
    }
    sleepChip.setVisibility(value.sleeping ? View.VISIBLE : View.GONE);
    boolean connected = value.connected;
    showText(status, conversation ? "● 对话中" : "● 待机");
    if (lastConversationStyle == null || lastConversationStyle != conversation) {
      status.setTextColor(conversation ? 0xffc9f5e6 : 0xffb6c0e2);
      android.text.SpannableString statusText =
          new android.text.SpannableString(conversation ? "● 对话中" : "● 待机");
      statusText.setSpan(new android.text.style.RelativeSizeSpan(.55f), 0, 1, 0);
      statusText.setSpan(
          new android.text.style.ForegroundColorSpan(conversation ? 0xff43d39e : 0xff8390bb),
          0, 1, 0);
      status.setText(statusText);
      status.setBackground(card(conversation ? 0x1243d39e : 0x0bffffff, 20));
      lastConversationStyle = conversation;
    }
    String heading, subtitle;
    String wakePhrase = displayedWakePhrase();
    if (value.microphoneMuted && value.state.equals("待机")) {
      heading = "麦克风已关";
      subtitle = "请打开设备的麦克风开关";
    } else if (value.state.equals("连接中")) {
      heading = "正在连接…";
      subtitle = "连上小智网关后就开始听";
    } else if (value.state.equals("聆听中")) {
      heading = microphoneHeld ? "松开发送" : "我在听";
      subtitle = microphoneHeld ? "说完松开手指" : "说完停一下，我就开始回答";
    } else if (value.state.equals("识别中")) {
      heading = "正在识别";
      subtitle = "你说完了，正在把话转成文字";
    } else if (value.state.equals("思考中")) {
      heading = (value.modelLabel.equals("选择模型") ? "模型" : value.modelLabel) + " 正在思考";
      subtitle = "复杂的问题可能要想一会儿";
    } else if (value.state.equals("回复中")) {
      heading = "正在回答";
      subtitle = "喊「" + wakePhrase + "」或点打断就能停下";
    } else {
      heading = "说「" + wakePhrase + "」";
      subtitle =
          value.reports > 0
              ? "有待汇报：点右上角收听，或叫一声「" + wakePhrase + "」"
              : value.sleeping && !connected
                  ? "睡眠时段：汇报不会自动念"
                  : "或者点麦克风；按住可以直接说";
    }
    if (!title.getText().toString().equals(heading)) {
      android.text.SpannableString formatted = new android.text.SpannableString(heading);
      int start = heading.indexOf('「');
      int end = heading.indexOf('」');
      if (start >= 0 && end >= start)
        formatted.setSpan(
            new android.text.style.ForegroundColorSpan(0xff9edaff), start, end + 1, 0);
      else if (value.state.equals("思考中")) {
        int modelEnd = heading.indexOf(" 正在思考");
        if (modelEnd > 0)
          formatted.setSpan(new android.text.style.ForegroundColorSpan(0xff9edaff), 0, modelEnd, 0);
      } else if (heading.equals("松开发送"))
        formatted.setSpan(new android.text.style.ForegroundColorSpan(0xff9edaff), 2, 4, 0);
      title.setText(formatted);
    }
    showText(hint, subtitle);
    orb.state(value.state, value.microphoneMuted);
    backdrop.state(value.state);
    boolean canAbort =
        connected
            && (value.state.equals("回复中")
                || value.state.equals("思考中")
                || value.state.equals("识别中"));
    abortButton.setEnabled(canAbort);
    abortButton.setAlpha(canAbort ? 1f : .22f);
    if (lastAbortStyle == null || lastAbortStyle != canAbort) {
      GradientDrawable abortBg = card(canAbort ? 0x14ffb547 : 0x0bffffff, 16);
      if (canAbort) abortBg.setStroke(dp(1), 0x55ffb547);
      abortButton.setBackground(abortBg);
      abortButton.setTextColor(canAbort ? 0xffffe1b0 : 0xffb6c0e2);
      lastAbortStyle = canAbort;
    }
    micRing.setVisibility(
        (value.state.equals("聆听中") || value.state.equals("识别中")) ? View.VISIBLE : View.GONE);
    boolean canEnd = active || value.state.equals("连接中");
    endButton.setEnabled(canEnd);
    endButton.setAlpha(canEnd ? 1f : .22f);
    showText(modelText, value.modelLabel);
    JSONObject selectedModel = null;
    for (int i = 0; i < value.options.length(); i++) {
      JSONObject candidate = value.options.optJSONObject(i);
      if (candidate != null && value.entry.equals(candidate.optString("entry"))) {
        selectedModel = candidate;
        break;
      }
    }
    int selectedLogo = selectedModel == null ? 0 : optionLogo(selectedModel);
    if (selectedLogo != lastModelLogo) {
      modelIcon.setVisibility(selectedLogo == 0 ? View.GONE : View.VISIBLE);
      if (selectedLogo != 0) modelIcon.setImageResource(selectedLogo);
      conversationIcon.setVisibility(selectedLogo == 0 ? View.GONE : View.VISIBLE);
      if (selectedLogo != 0) conversationIcon.setImageResource(selectedLogo);
      lastModelLogo = selectedLogo;
    }
    String modelDescription = selectedModel == null ? "" : selectedModel.optString("model", "");
    showText(modelDetail, modelDescription);
    modelDetail.setVisibility(modelDescription.isEmpty() ? View.GONE : View.VISIBLE);
    reports.setVisibility(value.reports > 0 ? View.VISIBLE : View.GONE);
    showText(reports, value.reports == 1 ? "▶ 有一条待汇报" : "▶ 有 " + value.reports + " 条待汇报");
    showText(
        warning,
        value.error
            + (value.error.isEmpty() || value.managementError.isEmpty() ? "" : " · ")
            + value.managementError);
    String key =
        value.entry
            + value.modelLabel
            + value.options.toString()
            + value.messages.toString()
            + value.state
            + microphoneHeld;
    if (!key.equals(rendered)) {
      // Rows are replaced below. Reset the old scroll offset before the
      // content shrinks, then position the new rows only after they are measured.
      scroll.scrollTo(0, 0);
      messages.removeAllViews();
      boolean hasPending =
          value.state.equals("聆听中") || value.state.equals("识别中") || value.state.equals("思考中");
      for (int i = 0; i < value.messages.length(); i++) {
        int messageMargin = (i + 1 < value.messages.length() || hasPending) ? dp(18) : 0;
        JSONObject m = value.messages.optJSONObject(i);
        if (m == null) continue;
        boolean user = m.optString("role").equals("user");
        String messageText = m.optString("text");
        boolean interrupted = m.optBoolean("interrupted");
        String body = messageText + (interrupted ? "  已打断" : "");
        long millis = ChatLog.timestampMillis(m);
        String timestamp =
            millis > 0 ? new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(millis)) : "";
        android.text.SpannableString content =
            new android.text.SpannableString(body + (timestamp.isEmpty() ? "" : "\n" + timestamp));
        if (interrupted) {
          int cutStart = messageText.length() + 2;
          content.setSpan(
              new android.text.style.ReplacementSpan() {
                @Override
                public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                    android.graphics.Paint.FontMetricsInt metrics) {
                  return Math.round(paint.measureText(text, start, end) * .56f) + dp(14);
                }

                @Override
                public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                    float x, int top, int y, int bottom, android.graphics.Paint paint) {
                  int oldColor = paint.getColor();
                  float oldSize = paint.getTextSize();
                  paint.setTextSize(oldSize * .56f);
                  float width = paint.measureText(text, start, end) + dp(12);
                  paint.setColor(0x1cffb547);
                  canvas.drawRoundRect(x, y - dp(13), x + width, y + dp(3), dp(5), dp(5), paint);
                  paint.setColor(0xffffc56e);
                  canvas.drawText(text, start, end, x + dp(6), y, paint);
                  paint.setColor(oldColor);
                  paint.setTextSize(oldSize);
                }
              },
              cutStart, body.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!timestamp.isEmpty()) {
          content.setSpan(
              new android.text.style.RelativeSizeSpan(.57f),
              body.length() + 1,
              content.length(),
              0);
          content.setSpan(
              new android.text.style.ForegroundColorSpan(user ? 0x99ffffff : 0x55ffffff),
              body.length() + 1,
              content.length(),
              0);
          if (user)
            content.setSpan(
                new android.text.style.AlignmentSpan.Standard(
                    android.text.Layout.Alignment.ALIGN_OPPOSITE),
                body.length() + 1,
                content.length(),
                android.text.Spanned.SPAN_PARAGRAPH);
        }
        TextView text = label("", 17, 0xfff2f5ff);
        if (user) text.setTextSize(15.3f);
        text.setText(content);
        text.setLineSpacing(user ? dp(3) : dp(6), 1f);
        text.setTextIsSelectable(true);
        text.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * .49));
        text.setPadding(
            user ? dp(15) : 0, user ? dp(12) : dp(10), user ? dp(15) : 0, user ? dp(12) : 0);
        if (user) {
          GradientDrawable bubble =
              new GradientDrawable(
                  GradientDrawable.Orientation.TL_BR, new int[] {0xff3f7bff, 0xff2f56e8});
          bubble.setCornerRadii(
              new float[] {dp(18), dp(18), dp(18), dp(18), dp(6), dp(6), dp(18), dp(18)});
          text.setBackground(bubble);
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
          lp.gravity = Gravity.END;
          lp.setMargins(dp(60), 0, 0, messageMargin);
          messages.addView(text, lp);
        } else {
          LinearLayout row = new LinearLayout(this);
          row.setGravity(Gravity.TOP);
          ImageView icon = new ImageView(this);
          int logo = 0;
          for (int j = 0; j < value.options.length(); j++) {
            JSONObject option = value.options.optJSONObject(j);
            if (option != null && value.entry.equals(option.optString("entry"))) {
              logo = optionLogo(option);
              break;
            }
          }
          boolean live = value.state.equals("回复中") && i == value.messages.length() - 1;
          if (logo != 0) {
            GradientDrawable iconBg = card(0xfff2f5ff, 10);
            icon.setBackground(iconBg);
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            icon.setImageResource(logo);
          } else icon.setVisibility(View.INVISIBLE);
          FrameLayout iconFrame = new FrameLayout(this);
          if (live && logo != 0) {
            GradientDrawable liveRing = card(0xff0b1128, 11);
            liveRing.setStroke(dp(1), 0xffa6c8ff);
            iconFrame.setBackground(liveRing);
          }
          iconFrame.addView(icon, new FrameLayout.LayoutParams(dp(29), dp(29), Gravity.CENTER));
          int iconFrameSize = live ? 35 : 29;
          LinearLayout.LayoutParams iconParams =
              new LinearLayout.LayoutParams(dp(iconFrameSize), dp(iconFrameSize));
          iconParams.setMargins(0, 0, dp(live ? 6 : 10), 0);
          row.addView(iconFrame, iconParams);
          row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
          lp.setMargins(0, 0, dp(20), messageMargin);
          messages.addView(row, lp);
        }
      }
      boolean listening = value.state.equals("聆听中") || value.state.equals("识别中");
      boolean thinking = value.state.equals("思考中");
      if (listening || thinking) {
        LinearLayout pending = new LinearLayout(this);
        pending.setGravity(Gravity.CENTER_VERTICAL);
        pending.setPadding(
            listening ? dp(14) : 0, listening ? dp(8) : 0, dp(14), listening ? dp(8) : 0);
        if (listening) {
          GradientDrawable pendingBg = card(0x1f19b4f0, 18);
          pendingBg.setStroke(dp(1), 0x407ff3ff);
          pending.setBackground(pendingBg);
        }
        if (thinking) {
          ImageView pendingLogo = new ImageView(this);
          if (selectedLogo != 0) {
            pendingLogo.setBackground(card(0xffffffff, 8));
            pendingLogo.setPadding(dp(4), dp(4), dp(4), dp(4));
            pendingLogo.setImageResource(selectedLogo);
          } else pendingLogo.setVisibility(View.INVISIBLE);
          LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(29), dp(29));
          logoParams.rightMargin = dp(10);
          pending.addView(pendingLogo, logoParams);
        }
        pending.addView(
            new PendingIndicator(
                this, thinking, playing -> service == null ? 0 : service.audioLevel(playing)),
            new LinearLayout.LayoutParams(thinking ? dp(40) : dp(32), dp(28)));
        TextView pendingLabel =
            label(
                thinking
                    ? "正在思考"
                    : value.state.equals("识别中") ? "正在识别…" : microphoneHeld ? "说完松开" : "正在听…",
                13,
                thinking ? 0xff7883ab : 0xffbff7ff);
        pendingLabel.setPadding(dp(8), 0, 0, 0);
        pending.addView(pendingLabel);
        LinearLayout.LayoutParams pendingParams = new LinearLayout.LayoutParams(-2, -2);
        pendingParams.gravity = listening ? Gravity.END : Gravity.START;
        pendingParams.setMargins(0, 0, 0, 0);
        messages.addView(pending, pendingParams);
      }
      rendered = key;
      final ScrollView chatScroll = scroll;
      final LinearLayout chatMessages = messages;
      chatMessages.getViewTreeObserver().addOnGlobalLayoutListener(
          new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
              if (chatMessages.getViewTreeObserver().isAlive())
                chatMessages.getViewTreeObserver().removeOnGlobalLayoutListener(this);
              if (messages == chatMessages && key.equals(rendered))
                chatScroll.scrollTo(0, Math.max(0, chatMessages.getHeight() - chatScroll.getHeight()));
            }
          });
    }
    boolean fresh =
        (value.state.equals("待机") || value.state.equals("连接中"))
            && value.messages.length() == 0;
    showText(
        conversationTitle,
        (value.modelLabel.equals("选择模型") ? "对话" : "与 " + value.modelLabel + " 的对话")
            + "  ·  "
            + (value.messages.length() == 0
                ? "新对话"
                : value.messages.length() + " 条消息 · " + (conversation ? "对话中" : "记录已保存")));
    conversationHead.setVisibility(fresh ? View.GONE : View.VISIBLE);
    clock.setVisibility(fresh ? View.GONE : View.VISIBLE);
    welcome.setVisibility(fresh ? View.VISIBLE : View.GONE);
    scroll.setVisibility(fresh ? View.GONE : View.VISIBLE);
    applyBrightness();
  }

  private void showTextInput() {
    inputRow.setVisibility(View.VISIBLE);
    input.requestFocus();
    android.view.inputmethod.InputMethodManager manager =
        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    manager.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
  }

  @Override
  public void onBackPressed() {
    if (inputRow != null && inputRow.getVisibility() == View.VISIBLE) {
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(input.getWindowToken(), 0);
      inputRow.setVisibility(View.GONE);
      return;
    }
    super.onBackPressed();
  }

  private void touch() {
    lastInteraction = SystemClock.elapsedRealtime();
    if (ambient != null && ambient.isShowing()) ambient.hide();
    if (black) setBlack(false);
  }

  private void setBlack(boolean value) {
    if (black == value) return;
    black = value;
    if (value && ambient != null && ambient.isShowing()) ambient.hide();
    curtain.setVisibility(value ? View.VISIBLE : View.GONE);
    orb.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
    applyBrightness();
    // While the curtain is up the activity is stopped and the tick no longer
    // runs; keep a slow re-check alive so a sleep-window black-out is lifted
    // once the window ends instead of waiting for a touch that may never come.
    // (The service's partial wake lock keeps this handler fed overnight.)
    ui.removeCallbacks(blackRecheck);
    if (value) ui.postDelayed(blackRecheck, BLACK_RECHECK_MS);
    else blackForSleep = false;
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      if (black) {
        restoringTouch = true;
        touch();
        return true;
      }
      touch();
    }
    if (restoringTouch) {
      if (event.getAction() == MotionEvent.ACTION_UP
          || event.getAction() == MotionEvent.ACTION_CANCEL) restoringTouch = false;
      return true;
    }
    return super.dispatchTouchEvent(event);
  }

  private void applyBrightness() {
    WindowManager.LayoutParams p = getWindow().getAttributes();
    float brightness =
        black
            ? .01f
            : Math.max(
                .02f,
                (snapshot != null && snapshot.sleeping
                        ? 10
                        : Protocol.percent(prefs.getInt("brightness", 80)))
                    / 100f);
    int keepFlag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    boolean keepAwake = !black && (prefs.getBoolean("keepScreen", true) || active);
    int flags = keepAwake ? p.flags | keepFlag : p.flags & ~keepFlag;
    // Reapplying unchanged window attributes every second can disturb system idle handling.
    if (p.screenBrightness != brightness || p.flags != flags) {
      p.screenBrightness = brightness;
      p.flags = flags;
      getWindow().setAttributes(p);
    }
    // A fullscreen dialog has its own brightness override; update the visible window too.
    if (settingsDialog != null && settingsDialog.isShowing()) {
      Window window = settingsDialog.getWindow();
      WindowManager.LayoutParams dialogParams = window.getAttributes();
      if (dialogParams.screenBrightness != brightness) {
        dialogParams.screenBrightness = brightness;
        window.setAttributes(dialogParams);
      }
    }
  }

  private void withService(Runnable action) {
    if (service != null) action.run();
    else {
      startVoice();
      Toast.makeText(this, "正在启动，请稍后操作", Toast.LENGTH_SHORT).show();
    }
  }

  private void startVoice() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !prefs.getBoolean("notificationAsked", false)) {
      prefs.edit().putBoolean("notificationAsked", true).apply();
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    }
    try {
      startForegroundService(new Intent(this, VoiceService.class));
      if (!binding)
        binding = bindService(new Intent(this, VoiceService.class), connection, BIND_AUTO_CREATE);
    } catch (RuntimeException e) {
      showText(warning, "语音服务启动失败，请重新打开应用");
    }
  }

  private void stopVoice() {
    if (binding) {
      if (service != null) service.detach();
      unbindService(connection);
      binding = bound = false;
    }
    service = null;
    stopService(new Intent(this, VoiceService.class));
    snapshot = null;
    active = false;
    showText(status, "服务已停止");
    showText(warning, "点击说话可重新启动服务");
    orb.state("待机", false);
    backdrop.state("待机");
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    touch();
    startVoice();
    ui.post(tick);
  }

  private void cancelHold() {
    if (micHold != null) ui.removeCallbacks(micHold);
    if (microphoneHeld && service != null) service.ptt(false);
    microphoneHeld = false;
  }

  @Override
  protected void onStop() {
    cancelHold();
    if (ambient != null) ambient.hide();
    visible = false;
    ui.removeCallbacks(tick);
    if (service != null) service.detach();
    super.onStop();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (service != null) service.attach(this);
  }

  @Override
  protected void onDestroy() {
    ui.removeCallbacksAndMessages(null);
    if (binding) {
      if (service != null) service.detach();
      unbindService(connection);
      binding = false;
    }
    super.onDestroy();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 10) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startVoice();
      else showText(warning, "语音对话需要麦克风权限");
    }
  }

  @Override
  public boolean onKeyDown(int key, KeyEvent event) {
    if ((key == KeyEvent.KEYCODE_HEADSETHOOK || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        && event.getRepeatCount() == 0) {
      touch();
      withService(() -> service.wake());
      return true;
    }
    return super.onKeyDown(key, event);
  }

  private EditText field(LinearLayout form, String title, String value, boolean secret) {
    form.addView(label(title, 14, 0xffb6c0e2));
    EditText e = new EditText(this);
    e.setTextColor(Color.WHITE);
    e.setText(value);
    e.setSingleLine(true);
    e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    e.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
    form.addView(e);
    return e;
  }

  private Switch check(LinearLayout form, String text, boolean value) {
    Switch c = new Switch(this);
    c.setText(text);
    styleSwitch(c);
    c.setTextColor(Color.WHITE);
    c.setChecked(value);
    form.addView(c);
    return c;
  }

  private void styleSwitch(Switch toggle) {
    toggle.setThumbDrawable(getDrawable(R.drawable.switch_thumb));
    toggle.setTrackDrawable(getDrawable(R.drawable.switch_track));
    toggle.setSwitchMinWidth(dp(62));
  }

  private LinearLayout settingsCard(LinearLayout panel, String title, String description) {
    LinearLayout section = column();
    section.setPadding(dp(20), dp(14), dp(20), dp(14));
    section.setBackground(card(0x0bffffff, 20));
    TextView name = label(title, 19, 0xfff2f5ff);
    name.setTypeface(null, Typeface.BOLD);
    section.addView(name);
    if (!description.isEmpty()) {
      TextView note = label(description, 13, 0xff7883ab);
      note.setPadding(0, dp(6), 0, dp(8));
      section.addView(note);
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    panel.addView(section, lp);
    return section;
  }

  private void setChildEnabled(View view, boolean enabled) {
    view.setEnabled(enabled);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) setChildEnabled(group.getChildAt(i), enabled);
    }
  }

  private void settingsActionRow(LinearLayout parent, Button... actions) {
    LinearLayout row = new LinearLayout(this);
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.topMargin = dp(12);
    parent.addView(row, rowParams);
    for (int i = 0; i < actions.length; i++) {
      Button action = actions[i];
      action.setMinWidth(0);
      action.setMinimumWidth(0);
      action.setPadding(dp(8), dp(8), dp(8), dp(8));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(56), 1);
      if (i > 0) params.leftMargin = dp(10);
      row.addView(action, params);
    }
  }

  private TextView timeControl(LinearLayout row, String labelText, String value, String prefKey) {
    LinearLayout tile = column();
    tile.setPadding(dp(16), dp(12), dp(16), dp(12));
    tile.setBackground(card(0x14ffffff, 18));
    tile.addView(label(labelText, 12, 0xff7883ab));
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    TextView time = label(value, 26, 0xfff2f5ff);
    time.setGravity(Gravity.CENTER);
    Button minus = button("−", () -> shiftTime(time, -30, prefKey));
    Button plus = button("+", () -> shiftTime(time, 30, prefKey));
    controls.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
    controls.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
    controls.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
    tile.addView(controls);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMargins(0, dp(8), dp(8), 0);
    row.addView(tile, params);
    return time;
  }

  private void shiftTime(TextView value, int adjustment, String prefKey) {
    int minutes = (Protocol.minutes(value.getText().toString()) + adjustment + 1440) % 1440;
    value.setText(String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60));
    prefs.edit().putString(prefKey, value.getText().toString()).apply();
    if (service != null) service.notifySettings();
  }

  private void settings() {
    final Dialog sheet = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
    LinearLayout root = column();
    root.setPadding(dp(24), dp(20), dp(24), dp(16));
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff0d1433, 0xff060a18}));
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout heading = column();
    TextView title = label("设置", 25, 0xfff2f5ff);
    title.setTypeface(null, Typeface.BOLD);
    heading.addView(title);
    heading.addView(label("这台设备自己的偏好，升级客户端也会保留", 13, 0xff7883ab));
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(button("✕", sheet::dismiss), new LinearLayout.LayoutParams(dp(52), dp(52)));
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout panel = column();
    panel.setPadding(0, dp(18), 0, dp(8));
    panel.setFocusableInTouchMode(true);
    panel.requestFocus();
    LinearLayout update = settingsCard(panel, "软件更新", "当前版本使用手动安装 APK。");
    LinearLayout bright = settingsCard(panel, "屏幕亮度", "");
    TextView brightnessTitle = (TextView) bright.getChildAt(0);
    bright.removeViewAt(0);
    LinearLayout brightnessHeader = new LinearLayout(this);
    brightnessHeader.setGravity(Gravity.CENTER_VERTICAL);
    brightnessHeader.addView(brightnessTitle, new LinearLayout.LayoutParams(-2, -2));
    TextView brightnessPercent = label("", 16, 0xff7883ab);
    brightnessPercent.setPadding(dp(12), 0, 0, 0);
    brightnessHeader.addView(brightnessPercent);
    bright.addView(brightnessHeader);
    SeekBar brightness = new SeekBar(this);
    brightness.setMax(100);
    brightness.setProgress(prefs.getInt("brightness", 80));
    brightness.setProgressDrawable(getDrawable(R.drawable.brightness_track));
    brightness.setThumb(getDrawable(R.drawable.brightness_thumb));
    brightnessPercent.setText(brightness.getProgress() + "%");
    brightness.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
            brightnessPercent.setText(value + "%");
            if (fromUser) {
              prefs.edit().putInt("brightness", value).apply();
              applyBrightness();
            }
          }

          public void onStartTrackingTouch(SeekBar seek) {}

          public void onStopTrackingTouch(SeekBar seek) {}
        });
    LinearLayout brightnessRow = new LinearLayout(this);
    brightnessRow.setGravity(Gravity.CENTER_VERTICAL);
    brightnessRow.addView(label("☼", 20, 0xff7883ab));
    brightnessRow.addView(brightness, new LinearLayout.LayoutParams(0, dp(48), 1));
    brightnessRow.addView(label("☼", 28, 0xff7883ab));
    LinearLayout.LayoutParams brightnessRowParams = new LinearLayout.LayoutParams(-1, -2);
    brightnessRowParams.topMargin = dp(8);
    bright.addView(brightnessRow, brightnessRowParams);
    TextView brightNote = label("睡眠时段内屏幕将调暗；时段结束后恢复设置的亮度。", 12, 0xff7883ab);
    brightNote.setPadding(0, dp(14), 0, 0);
    bright.addView(brightNote);
    LinearLayout standby = settingsCard(panel, "待机显示模式",
        "空闲 1 分钟后进入所选模式；触摸屏幕或说唤醒词可返回。两种模式不会自动切换。");
    RadioGroup standbyModes = new RadioGroup(this);
    standbyModes.setOrientation(LinearLayout.HORIZONTAL);
    RadioButton infoMode = new RadioButton(this);
    infoMode.setId(View.generateViewId());
    infoMode.setText("信息屏模式");
    infoMode.setTextColor(Color.WHITE);
    RadioButton photoMode = new RadioButton(this);
    photoMode.setId(View.generateViewId());
    photoMode.setText("相框模式");
    photoMode.setTextColor(Color.WHITE);
    standbyModes.addView(infoMode, new RadioGroup.LayoutParams(0, dp(56), 1));
    standbyModes.addView(photoMode, new RadioGroup.LayoutParams(0, dp(56), 1));
    standbyModes.check(prefs.getBoolean(AMBIENT_MODE_PREF, false)
        ? photoMode.getId() : infoMode.getId());
    standbyModes.setOnCheckedChangeListener((group, checkedId) -> {
      prefs.edit().putBoolean(AMBIENT_MODE_PREF, checkedId == photoMode.getId()).apply();
      touch();
    });
    standby.addView(standbyModes);
    TextView photoNote = label("相框轮播本地照片；没有照片时显示提示。", 12, 0xff7883ab);
    standby.addView(photoNote);
    LinearLayout screen = settingsCard(panel, "自动熄屏", "一段时间没碰屏幕、也没在对话时熄屏；碰一下屏幕或说唤醒词就亮。关闭后屏幕保持常亮。");
    LinearLayout screenHeader = new LinearLayout(this);
    screenHeader.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout screenText = column();
    while (screen.getChildCount() > 0) {
      View child = screen.getChildAt(0);
      screen.removeViewAt(0);
      screenText.addView(child);
    }
    LinearLayout.LayoutParams screenTextParams = new LinearLayout.LayoutParams(0, -2, 1);
    screenTextParams.rightMargin = dp(12);
    screenHeader.addView(screenText, screenTextParams);
    Switch auto = new Switch(this);
    auto.setContentDescription("启用自动黑屏");
    styleSwitch(auto);
    auto.setChecked(prefs.getBoolean("autoOff", true));
    auto.setOnCheckedChangeListener(
        (v, checked) -> {
          prefs.edit().putBoolean("autoOff", checked).apply();
          touch();
        });
    screenHeader.addView(auto);
    screen.addView(screenHeader);
    Integer[] choices = {1, 3, 5, 10, 30};
    final int[] selectedMinutes = {prefs.getInt("offMinutes", 5)};
    TextView offNote = label("", 12, 0xff7883ab);
    Runnable updateOffNote =
        () -> offNote.setText(auto.isChecked()
            ? selectedMinutes[0] + " 分钟没碰屏幕、也没在对话，就熄屏"
            : "屏幕一直亮着，不会自动熄屏");
    LinearLayout minuteRow = new LinearLayout(this);
    for (int choice : choices) {
      Button minute = button(choice + " 分钟", () -> {});
      LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(50), 1);
      mp.setMargins(0, 0, dp(8), 0);
      minuteRow.addView(minute, mp);
      minute.setOnClickListener(
          v -> {
            selectedMinutes[0] = choice;
            prefs.edit().putInt("offMinutes", choice).apply();
            updateOffNote.run();
            touch();
            for (int i = 0; i < minuteRow.getChildCount(); i++) {
              Button item = (Button) minuteRow.getChildAt(i);
              item.setBackground(choices[i] == choice ? selectedChoice() : card(0x14ffffff, 16));
              item.setTextColor(choices[i] == choice ? 0xff081024 : 0xffb6c0e2);
            }
          });
      minute.setBackground(choice == selectedMinutes[0] ? selectedChoice() : card(0x14ffffff, 16));
      minute.setTextColor(choice == selectedMinutes[0] ? 0xff081024 : 0xffb6c0e2);
    }
    LinearLayout.LayoutParams minuteParams = new LinearLayout.LayoutParams(-1, -2);
    minuteParams.bottomMargin = dp(16);
    panel.addView(minuteRow, minuteParams);
    offNote.setPadding(dp(4), 0, 0, dp(16));
    panel.addView(offNote);
    updateOffNote.run();
    minuteRow.setAlpha(auto.isChecked() ? 1f : .35f);
    for (int i = 0; i < minuteRow.getChildCount(); i++)
      minuteRow.getChildAt(i).setEnabled(auto.isChecked());
    auto.setOnCheckedChangeListener(
        (v, checked) -> {
          prefs.edit().putBoolean("autoOff", checked).apply();
          updateOffNote.run();
          touch();
          minuteRow.setAlpha(checked ? 1f : .35f);
          for (int i = 0; i < minuteRow.getChildCount(); i++)
            minuteRow.getChildAt(i).setEnabled(checked);
        });
    LinearLayout sleepSection = settingsCard(panel, "睡眠时段", "时段内后台任务汇报不会自动播报，屏幕调暗。叫它的话照常回答。");
    LinearLayout sleepHeader = new LinearLayout(this);
    sleepHeader.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout sleepText = column();
    while (sleepSection.getChildCount() > 0) {
      View child = sleepSection.getChildAt(0);
      sleepSection.removeViewAt(0);
      sleepText.addView(child);
    }
    sleepHeader.addView(sleepText, new LinearLayout.LayoutParams(0, -2, 1));
    Switch sleep = new Switch(this);
    styleSwitch(sleep);
    sleep.setContentDescription("启用睡眠时段");
    sleep.setChecked(prefs.getBoolean("sleep", false));
    sleepHeader.addView(sleep);
    sleepSection.addView(sleepHeader);
    LinearLayout timeRow = new LinearLayout(this);
    LinearLayout.LayoutParams timeRowParams = new LinearLayout.LayoutParams(-1, -2);
    timeRowParams.bottomMargin = dp(16);
    panel.addView(timeRow, timeRowParams);
    TextView start =
        timeControl(timeRow, "开始", prefs.getString("sleepStart", "22:00"), "sleepStart");
    TextView end = timeControl(timeRow, "结束", prefs.getString("sleepEnd", "07:00"), "sleepEnd");
    TextView sleepNote = label("", 12, 0xff7883ab);
    sleepNote.setPadding(dp(4), 0, 0, dp(8));
    panel.addView(sleepNote);
    Runnable updateSleepNote = () -> {
      java.util.Calendar now = java.util.Calendar.getInstance();
      boolean activeSleep = Protocol.inSleep(
          now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE),
          Protocol.minutes(start.getText().toString()), Protocol.minutes(end.getText().toString()));
      sleepNote.setText(!sleep.isChecked()
          ? "睡眠时段已关闭：汇报照常播报，音量和亮度照常"
          : activeSleep ? "现在是睡眠时段，到 " + end.getText() + " 结束"
              : "每天 " + start.getText() + " 到 " + end.getText() + " 是睡眠时段，现在不在");
    };
    android.text.TextWatcher timeWatcher = new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) { sleepNote.post(updateSleepNote); }
      public void afterTextChanged(android.text.Editable s) {}
    };
    start.addTextChangedListener(timeWatcher);
    end.addTextChangedListener(timeWatcher);
    updateSleepNote.run();
    setChildEnabled(timeRow, sleep.isChecked());
    timeRow.setAlpha(sleep.isChecked() ? 1f : .35f);
    sleep.setOnCheckedChangeListener(
        (v, checked) -> {
          prefs.edit().putBoolean("sleep", checked).apply();
          updateSleepNote.run();
          setChildEnabled(timeRow, checked);
          timeRow.setAlpha(checked ? 1f : .35f);
          if (service != null) service.notifySettings();
          applyBrightness();
        });
    LinearLayout historySection =
        settingsCard(panel, "聊天记录", "清除服务器保存的所有模型记录和记忆；Claude Code、Grok Bot 自己的会话不受影响。");
    LinearLayout historyHeader = new LinearLayout(this);
    historyHeader.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout historyText = column();
    while (historySection.getChildCount() > 0) {
      View child = historySection.getChildAt(0);
      historySection.removeViewAt(0);
      historyText.addView(child);
    }
    historyHeader.addView(historyText, new LinearLayout.LayoutParams(0, -2, 1));
    historyHeader.addView(
        button("清除", () -> confirmClear(false)), new LinearLayout.LayoutParams(dp(100), dp(48)));
    historySection.addView(historyHeader);
    TextView clearMemory = label("只清除记忆、保留记录", 13, 0xff6c8cff);
    clearMemory.setPadding(dp(4), 0, 0, dp(16));
    clearMemory.setOnClickListener(v -> confirmClear(true));
    panel.addView(clearMemory);
    LinearLayout connectionSection = settingsCard(panel, "连接与语音", "填写服务器 IP，连接地址自动配置。");
    EditText serverIp = field(connectionSection, "服务器 IP（例如 192.168.1.10）",
        ServerAddress.savedIp(prefs.getString("serverIp", ""),
            prefs.getString("ws", ""), prefs.getString("http", "")), false);
    serverIp.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    Switch keep = check(connectionSection, "保持应用窗口常亮", prefs.getBoolean("keepScreen", true));
    Switch wake = check(connectionSection, "启用离线唤醒词", prefs.getBoolean("wake", true));
    EditText wakePhrase = field(connectionSection, "自定义唤醒词（2 至 12 个汉字，留空恢复默认）",
        prefs.getString(WakePhrase.KEY, ""), false);
    Button applyWakePhrase = button("应用唤醒词", () -> {
      String phrase = wakePhrase.getText().toString().trim();
      try {
        if (!phrase.isEmpty()) WakePhrase.keywords(getAssets(), phrase);
        prefs.edit().putString(WakePhrase.KEY, phrase).apply();
        if (snapshot != null) update(snapshot);
        else {
          android.text.SpannableString text =
              new android.text.SpannableString("说「" + displayedWakePhrase() + "」");
          text.setSpan(new android.text.style.ForegroundColorSpan(0xff9edaff), 1, text.length(), 0);
          title.setText(text);
        }
        Toast.makeText(this, phrase.isEmpty() ? "已恢复默认唤醒词" : "唤醒词已应用：" + phrase,
            Toast.LENGTH_SHORT).show();
      } catch (Exception e) {
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });
    connectionSection.addView(applyWakePhrase);
    Switch verify = check(connectionSection, "唤醒二次确认", prefs.getBoolean("wakeVerify", false));
    Switch recognitionInput = check(connectionSection, "录音使用语音识别模式（关闭为通话模式）",
        prefs.getBoolean("recognitionInput", false));
    Switch inputNoiseSuppression = check(connectionSection, "启用录音降噪",
        prefs.getBoolean("inputNoiseSuppression", true));
    Button textInput =
        button(
            "文字输入",
            () -> {
              sheet.dismiss();
              showTextInput();
            });
    Button sync = button("立即同步", () -> withService(() -> service.refreshManagement()));
    sync.setContentDescription("模型与历史立即同步");
    Button clearLocal = button("清除本机记录", () -> withService(() -> service.clearLocal()));
    settingsActionRow(connectionSection, textInput, sync, clearLocal);
    Button stop = button("停止语音服务", this::stopVoice);
    stop.setTextColor(0xffffacb8);
    FrameLayout panelHolder = new FrameLayout(this);
    panelHolder.addView(
        panel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    panelHolder.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int desiredWidth = Math.min(dp(600), right - left);
          if (desiredWidth > 0 && panel.getLayoutParams().width != desiredWidth) {
            ViewGroup.LayoutParams params = panel.getLayoutParams();
            params.width = desiredWidth;
            panel.setLayoutParams(params);
          }
        });
    scroll.addView(panelHolder);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Button save =
        button(
            "保存并重连",
            () -> {
              try {
                ServerAddress address = new ServerAddress(serverIp.getText().toString());
                Protocol.minutes(start.getText().toString());
                Protocol.minutes(end.getText().toString());
                prefs
                    .edit()
                    .putString("serverIp", address.ip)
                    .putString("ws", address.websocket)
                    .putString("http", address.management)
                    .putBoolean("wake", wake.isChecked())
                    .putBoolean("wakeVerify", verify.isChecked())
                    .putBoolean("recognitionInput", recognitionInput.isChecked())
                    .putBoolean("inputNoiseSuppression", inputNoiseSuppression.isChecked())
                    .putBoolean("keepScreen", keep.isChecked())
                    .putBoolean("autoOff", auto.isChecked())
                    .putInt("offMinutes", selectedMinutes[0])
                    .putInt("brightness", brightness.getProgress())
                    .putBoolean("sleep", sleep.isChecked())
                    .putString("sleepStart", start.getText().toString())
                    .putString("sleepEnd", end.getText().toString())
                    .apply();
                if (service != null) service.reconnect();
                touch();
                applyBrightness();
                sheet.dismiss();
              } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
              }
            });
    save.setBackground(selectedChoice());
    save.setTextColor(0xff081024);
    if (android.os.Build.VERSION.SDK_INT >= 31) {
      Button bluetooth = button("蓝牙配对", () -> requestPermissions(new String[] {
          "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT"}, 12));
      settingsActionRow(connectionSection, bluetooth, stop, save);
    } else settingsActionRow(connectionSection, stop, save);
    SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
        (changed, key) ->
            runOnUiThread(
                () -> {
                  if (!sheet.isShowing()) return;
                  if ("brightness".equals(key))
                    brightness.setProgress(changed.getInt("brightness", 80));
                  else if ("autoOff".equals(key)
                      && auto.isChecked() != changed.getBoolean("autoOff", true))
                    auto.setChecked(changed.getBoolean("autoOff", true));
                  else if ("sleep".equals(key)
                      && sleep.isChecked() != changed.getBoolean("sleep", false))
                    sleep.setChecked(changed.getBoolean("sleep", false));
                  else if ("sleepStart".equals(key))
                    showText(start, changed.getString("sleepStart", "22:00"));
                  else if ("sleepEnd".equals(key))
                    showText(end, changed.getString("sleepEnd", "07:00"));
                  else if ("offMinutes".equals(key)) {
                    selectedMinutes[0] = changed.getInt("offMinutes", 5);
                    for (int i = 0; i < minuteRow.getChildCount(); i++) {
                      Button item = (Button) minuteRow.getChildAt(i);
                      boolean selected = choices[i] == selectedMinutes[0];
                      item.setBackground(card(selected ? 0xff8fa6ff : 0x14ffffff, 16));
                      item.setTextColor(selected ? 0xff081024 : 0xffb6c0e2);
                    }
                  }
                });
    sheet.setContentView(root);
    sheet.setOnShowListener(
        v -> {
          settingsDialog = sheet;
          overlayOpen = true;
          touch();
          applyBrightness();
          prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        });
    sheet.setOnDismissListener(
        v -> {
          prefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
          settingsDialog = null;
          overlayOpen = false;
          touch();
        });
    sheet.show();
    applyNavigationVisibility(sheet.getWindow());
    sheet
        .getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }

  private void confirmClear(boolean memory) {
    new AlertDialog.Builder(this)
        .setTitle(memory ? "清除服务器记忆？" : "清除服务器聊天记录与记忆？")
        .setMessage("此操作影响这台设备的所有模型。Claude Code、Grok Bot 自己保存的会话不受影响。")
        .setNegativeButton("取消", null)
        .setPositiveButton("确认清除", (d, w) -> withService(() -> service.clearServer(memory)))
        .show();
  }

  private int optionLogo(JSONObject option) {
    String identity =
        (option.optString("vendor")
                + " "
                + option.optString("model")
                + " "
                + option.optString("entry"))
            .toLowerCase(Locale.ROOT);
    if ("muse".equalsIgnoreCase(option.optString("kind")) || identity.contains("muse"))
      return R.drawable.logo_muse;
    String logo =
        identity.contains("claude")
            ? "claude_color"
            : identity.contains("openai") || identity.contains("gpt")
                ? "openai"
                : identity.contains("gemini")
                    ? "gemini_color"
                    : identity.contains("deepseek")
                        ? "deepseek_color"
                        : identity.contains("qwen") || identity.contains("qwq")
                            ? "qwen_color"
                            : identity.contains("doubao")
                                ? "doubao_color"
                                : identity.contains("glm") || identity.contains("zhipu")
                                    ? "zhipu_color"
                                    : identity.contains("kimi") || identity.contains("moonshot")
                                        ? "kimi_color"
                                        : identity.contains("grok") ? "grok" : "";
    int logoId =
        logo.isEmpty()
            ? 0
            : getResources().getIdentifier("logo_" + logo, "drawable", getPackageName());
    return logoId;
  }

  private void models() {
    if (snapshot == null || snapshot.options.length() == 0) {
      withService(() -> service.refreshManagement());
      Toast.makeText(this, "暂无模型列表，请检查管理地址与服务器", Toast.LENGTH_LONG).show();
      return;
    }
    JSONArray options = snapshot.options;
    final Dialog sheet = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
    LinearLayout root = column();
    root.setPadding(dp(24), dp(20), dp(24), dp(24));
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xff0d1433, 0xff060a18}));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout heading = column();
    TextView headingTitle = label("选择模型", 25, 0xfff2f5ff);
    headingTitle.setTypeface(null, Typeface.BOLD);
    heading.addView(headingTitle);
    heading.addView(label("切换后，新的提问交给它回答；每个模型各自保留对话", 13, 0xff7883ab));
    bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(button("✕", sheet::dismiss), new LinearLayout.LayoutParams(dp(52), dp(52)));
    root.addView(bar);
    ScrollView scroller = new ScrollView(this);
    GridLayout grid = new GridLayout(this);
    grid.setColumnCount(
        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
            ? 4
            : 2);
    grid.setPadding(0, dp(20), 0, 0);
    int cols = grid.getColumnCount();
    int width = (getResources().getDisplayMetrics().widthPixels - dp(48 + (cols - 1) * 12)) / cols;
    for (int i = 0; i < options.length(); i++) {
      JSONObject option = options.optJSONObject(i);
      if (option == null) continue;
      boolean selected = option.optString("entry").equals(snapshot.entry);
      LinearLayout tile = column();
      tile.setPadding(dp(16), dp(16), dp(16), dp(8));
      GradientDrawable tileBackground = card(selected ? 0x306c8cff : 0x0bffffff, 18);
      if (selected) tileBackground.setStroke(dp(1), 0xb07fa0ff);
      tile.setBackground(tileBackground);
      TextView icon = label((option.optString("label", "模") + "模").substring(0, 1), 22, 0xff111733);
      icon.setGravity(Gravity.CENTER);
      icon.setTypeface(null, Typeface.BOLD);
      icon.setBackground(card(0xfff2f5ff, 12));
      int logoId = optionLogo(option);
      FrameLayout tileHead = new FrameLayout(this);
      if (selected) {
        TextView badge = label("使用中", 11, 0xff081024);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable badgeBackground =
            new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] {0xff8ff0ff, 0xff8fa6ff});
        badgeBackground.setCornerRadius(dp(16));
        badge.setBackground(badgeBackground);
        tileHead.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
      }
      if (logoId != 0) {
        ImageView image = new ImageView(this);
        image.setBackground(card(0xfff2f5ff, 12));
        image.setPadding(dp(7), dp(7), dp(7), dp(7));
        image.setImageResource(logoId);
        tileHead.addView(
            image, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START | Gravity.TOP));
      } else
        tileHead.addView(
            icon, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START | Gravity.TOP));
      tile.addView(tileHead, new LinearLayout.LayoutParams(-1, dp(40)));
      TextView name = label(option.optString("label", option.optString("entry")), 16, 0xfff2f5ff);
      name.setIncludeFontPadding(false);
      LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
      titleParams.topMargin = dp(10);
      tile.addView(name, titleParams);
      TextView detail = label(option.optString("model", option.optString("entry")), 11, 0xff7883ab);
      detail.setTypeface(Typeface.MONOSPACE);
      detail.setIncludeFontPadding(false);
      detail.setSingleLine(true);
      detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
      tile.addView(detail);
      String vendor = option.optString("vendor");
      String company =
          vendor.contains("claude")
              ? "Anthropic"
              : vendor.equals("gemini")
                  ? "Google"
                  : vendor.equals("qwen")
                      ? "阿里通义"
                      : vendor.equals("doubao")
                          ? "字节豆包"
                          : vendor.equals("glm")
                              ? "智谱"
                              : vendor.equals("kimi")
                                  ? "月之暗面"
                                  : vendor.contains("grok") ? "xAI" : option.optString("label", "");
      TextView companyLabel = label(company, 11, 0xffb6c0e2);
      companyLabel.setIncludeFontPadding(false);
      tile.addView(companyLabel);
      tile.setOnClickListener(
          v -> {
            if (snapshot == null || !option.optString("entry").equals(snapshot.entry))
              withService(() -> service.chooseModel(option));
            sheet.dismiss();
          });
      GridLayout.LayoutParams cell = new GridLayout.LayoutParams();
      cell.width = width;
      cell.height = dp(132);
      cell.setMargins(0, 0, i % cols == cols - 1 ? 0 : dp(12), dp(12));
      grid.addView(tile, cell);
    }
    scroller.addView(grid);
    scroller.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int available = right - left;
          if (available <= 0 || available == oldRight - oldLeft) return;
          int count = available >= dp(640) ? 4 : 2;
          grid.setColumnCount(count);
          int itemWidth = (available - dp((count - 1) * 12)) / count;
          for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            GridLayout.LayoutParams params = (GridLayout.LayoutParams) item.getLayoutParams();
            params.width = itemWidth;
            params.setMargins(0, 0, i % count == count - 1 ? 0 : dp(12), dp(12));
            item.setLayoutParams(params);
          }
        });
    root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    sheet.setContentView(root);
    sheet.setOnShowListener(
        v -> {
          overlayOpen = true;
          touch();
        });
    sheet.setOnDismissListener(
        v -> {
          overlayOpen = false;
          touch();
        });
    sheet.show();
    applyNavigationVisibility(sheet.getWindow());
  }
}
