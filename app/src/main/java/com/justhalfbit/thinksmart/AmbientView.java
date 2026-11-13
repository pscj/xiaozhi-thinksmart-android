package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The ambient (standby) face: an information screen that takes over the kiosk when nothing is
 * happening, and a photo frame behind it.
 *
 * <p>MainActivity owns the when: it shows this view after 60 s with no conversation and no
 * touch, using the selected info or photo mode, and hides it on any touch or wake-word hit
 * (activity-level dispatchTouchEvent and the snapshot flow both funnel into its touch()). This
 * view only renders and never touches the voice path — the wake word keeps running in
 * VoiceService whatever is on screen, and hiding is purely visual.
 *
 * <p>Screen care, as specced with the web prototype: the info layer shifts by a few px every
 * minute against the same-colour background (burn-in). Night dimming stays the activity's job
 * (its sleep-window brightness covers this view too, since it lives in the same window).
 *
 * <p>Photos come from a {@code photos/} folder in the app's external files dir
 * (Android/data/com.justhalfbit.thinksmart/files/photos) — put .jpg/.png/.webp files there;
 * with no photos the frame mode displays an empty-state hint.
 *
 * <p>The info screen sits on a background photo under a dark scrim (the web prototype's info
 * screen had one too): the {@code photos/} pictures when the folder holds any, slowly
 * rotating, otherwise the bundled defaults in {@code assets/ambient_bg} — the same four
 * scenic photos the web prototype used, rotating the same way. The photo frame mode is
 * unaffected: it still shows its pictures full-screen at full brightness above everything.
 */
final class AmbientView extends FrameLayout {

  enum Mode {
    INFO,
    PHOTO
  }

  Mode mode = Mode.INFO;

  private final FrameLayout bgLayer;
  private final ImageView bgA, bgB;
  private final View bgScrim;
  private final LinearLayout infoLayer;
  private final FrameLayout photoLayer;
  private final TextView timeText, dateText, wxCity, wxFallback, newsTag, photoClock;
  private final LinearLayout leftCol;
  private final LinearLayout wxCluster;
  private final WeatherIconView wxIcon;
  private final GlyphTextView wxCondGlyph, wxTempGlyph;
  private final View wxDivider;
  private final LinearLayout wxDetails;
  private final LinearLayout wxForecast;
  private final FrameLayout[] fcCards = new FrameLayout[3];
  private final WeatherIconView[] fcIcons = new WeatherIconView[3];
  private final LinearLayout[] fcLines = new LinearLayout[3];
  private final TextView[] fcLine1 = new TextView[3];
  private final TextView[] fcLine2 = new TextView[3];
  private final LinearLayout newsList;
  private final LinearLayout dressPanel;
  private final LinearLayout dressRows;
  private final LinearLayout wakeBar;
  private final TextView wakeText;
  private final ImageView photoA, photoB;
  private final TextView photoEmpty;

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable clockTick =
      new Runnable() {
        public void run() {
          if (!isShowing()) return;
          renderClock();
          if (mode == Mode.INFO) refreshData();
          ui.postDelayed(this, 1000);
        }
      };
  private final Runnable shiftTick =
      new Runnable() {
        public void run() {
          if (!isShowing()) return;
          shiftStep = (shiftStep + 1) % SHIFTS.length;
          infoLayer.setTranslationX(SHIFTS[shiftStep][0] * density());
          infoLayer.setTranslationY(SHIFTS[shiftStep][1] * density());
          ui.postDelayed(this, SHIFT_MS);
        }
      };
  private final Runnable photoTick =
      new Runnable() {
        public void run() {
          if (!isShowing() || mode != Mode.PHOTO || photoFiles.isEmpty()) return;
          photoIndex = (photoIndex + 1) % photoFiles.size();
          showPhoto(photoIndex);
          ui.postDelayed(this, PHOTO_MS);
        }
      };
  private final Runnable bgTick =
      new Runnable() {
        public void run() {
          if (!isShowing() || mode != Mode.INFO) return;
          if (bgCount() > 1) {
            bgIndex = (bgIndex + 1) % bgCount();
            showBackground(bgIndex, true);
          }
          ui.postDelayed(this, BG_MS);
        }
      };

  private AmbientRepository repo;
  private File photosDir;
  private Supplier<String> wakePhrase;
  private long lastWeatherFetchMs, lastNewsFetchMs;

  private List<File> photoFiles = new ArrayList<>();
  private int photoIndex;
  /** The "tomorrow" the dressing panel currently shows; rebuilt when the date rolls over. */
  private LocalDate dressDate;
  private boolean frontIsA = true;
  private Bitmap frontBitmap;
  private int shiftStep;

  // Info-screen background: the user's photos when the folder has any, else the bundled
  // defaults (bgAssetNames holds file names inside assets/ambient_bg).
  private List<File> bgFiles = new ArrayList<>();
  private List<String> bgAssetNames = new ArrayList<>();
  private int bgIndex;
  private boolean bgFrontIsA = true;
  private Bitmap bgFrontBitmap;

  AmbientView(Context context) {
    super(context);
    setBackgroundColor(0xff050813);
    setVisibility(GONE);

    // ---- background layer: photo at the very bottom, dark scrim over it ----
    bgLayer = new FrameLayout(context);
    addView(bgLayer, new FrameLayout.LayoutParams(-1, -1));
    bgA = photoImage(context);
    bgB = photoImage(context);
    bgLayer.addView(bgA, new FrameLayout.LayoutParams(-1, -1));
    bgLayer.addView(bgB, new FrameLayout.LayoutParams(-1, -1));
    bgScrim = new View(context);
    bgScrim.setBackgroundColor(SCRIM_COLOR);
    bgLayer.addView(bgScrim, new FrameLayout.LayoutParams(-1, -1));

    // ---- info layer ----
    // Bottom padding reserves the wake bar's strip: the news region ends above the bar, so
    // the last item can never slide under it.
    infoLayer = new LinearLayout(context);
    infoLayer.setOrientation(LinearLayout.VERTICAL);
    infoLayer.setPadding(dp(48), dp(20), dp(48), dp(WAKE_BAR_H + 4));
    addView(infoLayer, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout top = new LinearLayout(context);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.TOP);
    infoLayer.addView(top, new LinearLayout.LayoutParams(-1, -2));

    // The clock block: taller than the weather side it shares the top row with (the
    // 96 sp line reserves the font's full top..bottom), so renderWeather lifts it by
    // the measured difference — see alignClockBlock — until the date line's bottom
    // meets the weather cluster's bottom.
    leftCol = new LinearLayout(context);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    top.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 1.2f));
    timeText = text("", 96, 0xfff2f5ff);
    timeText.setTypeface(null, Typeface.BOLD);
    leftCol.addView(timeText);
    dateText = text("", 24, 0xffb6c0e2);
    LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
    dateParams.topMargin = dp(6);
    leftCol.addView(dateText, dateParams);

    LinearLayout right = new LinearLayout(context);
    right.setOrientation(LinearLayout.VERTICAL);
    right.setGravity(Gravity.END);
    top.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
    wxCity = text("", 20, 0xffb6c0e2);
    wxCity.setGravity(Gravity.END);
    right.addView(wxCity);
    // Graceful-degradation line: shown only when no reading is available at all, in
    // place of the whole weather cluster, so the corner never shows a half-empty layout.
    wxFallback = text("天气暂不可用", 24, 0xfff2f5ff);
    wxFallback.setGravity(Gravity.END);
    wxFallback.setVisibility(GONE);
    LinearLayout.LayoutParams fallbackParams = new LinearLayout.LayoutParams(-1, -2);
    fallbackParams.topMargin = dp(16);
    right.addView(wxFallback, fallbackParams);

    // Weather cluster (the approved ref11 composition): one compact group, right-aligned
    // as a whole under the city name — an icon column (drawn icon with its condition text
    // centred directly under it), the live temperature as exact glyphs spanning that
    // column's full height, a hairline, then the detail rows (name fixed-width, the
    // value column measured to its widest value so the two sit tight together). No AQI
    // (the hub has none), no ultraviolet
    // row, no "updated at" line: just the cluster.
    wxCluster = new LinearLayout(context);
    wxCluster.setOrientation(LinearLayout.HORIZONTAL);
    wxCluster.setGravity(Gravity.END | Gravity.TOP);
    LinearLayout.LayoutParams clusterParams = new LinearLayout.LayoutParams(-1, -2);
    clusterParams.topMargin = dp(10);
    right.addView(wxCluster, clusterParams);

    LinearLayout iconCol = new LinearLayout(context);
    iconCol.setOrientation(LinearLayout.VERTICAL);
    iconCol.setGravity(Gravity.CENTER_HORIZONTAL);
    wxCluster.addView(iconCol, new LinearLayout.LayoutParams(-2, -2));
    wxIcon = new WeatherIconView(context);
    iconCol.addView(wxIcon, new LinearLayout.LayoutParams(dp(WX_ICON_DP), dp(WX_ICON_DP)));
    wxCondGlyph = new GlyphTextView(context, false);
    LinearLayout.LayoutParams condParams = new LinearLayout.LayoutParams(-2, -2);
    condParams.topMargin = dp(WX_COND_GAP_DP);
    iconCol.addView(wxCondGlyph, condParams);

    wxTempGlyph = new GlyphTextView(context, true);
    LinearLayout.LayoutParams tempGlyphParams = new LinearLayout.LayoutParams(-2, -2);
    tempGlyphParams.leftMargin = dp(10);
    wxCluster.addView(wxTempGlyph, tempGlyphParams);

    wxDivider = new View(context);
    wxDivider.setBackgroundColor(0x59ffffff);
    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(80));
    dividerParams.leftMargin = dp(14);
    dividerParams.rightMargin = dp(14);
    wxCluster.addView(wxDivider, dividerParams);

    wxDetails = new LinearLayout(context);
    wxDetails.setOrientation(LinearLayout.VERTICAL);
    wxCluster.addView(wxDetails, new LinearLayout.LayoutParams(dp(WX_DETAILS_W_DP), dp(80)));

    // ---- three-day forecast strip: equal translucent cards under the cluster ----
    wxForecast = new LinearLayout(context);
    wxForecast.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams forecastParams =
        new LinearLayout.LayoutParams(-1, dp(FORECAST_H_DP));
    forecastParams.topMargin = dp(12);
    infoLayer.addView(wxForecast, forecastParams);
    for (int i = 0; i < fcCards.length; i++) {
      FrameLayout card = new FrameLayout(context);
      GradientDrawable cardBg = new GradientDrawable();
      cardBg.setColor(0x2b141d33);
      cardBg.setCornerRadius(dp(14));
      cardBg.setStroke(dp(1), 0x40ffffff);
      card.setBackground(cardBg);
      LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, -1, 1);
      if (i > 0) cardParams.leftMargin = dp(16);
      wxForecast.addView(card, cardParams);
      fcCards[i] = card;
      // Icon + two lines as one group, centred in the card (the mock's composition).
      LinearLayout group = new LinearLayout(context);
      group.setOrientation(LinearLayout.HORIZONTAL);
      group.setGravity(Gravity.CENTER_VERTICAL);
      card.addView(group, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
      fcIcons[i] = new WeatherIconView(context);
      group.addView(fcIcons[i], new LinearLayout.LayoutParams(dp(40), dp(40)));
      LinearLayout lines = new LinearLayout(context);
      lines.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams linesParams = new LinearLayout.LayoutParams(-2, -2);
      linesParams.leftMargin = dp(10);
      group.addView(lines, linesParams);
      fcLines[i] = lines;
      fcLine1[i] = text("", 19, 0xfff2f5ff);
      fcLine1[i].setTypeface(null, Typeface.BOLD);
      fcLine1[i].setSingleLine(true);
      lines.addView(fcLine1[i]);
      fcLine2[i] = text("", 19, 0xffb6c0e2);
      fcLine2[i].setSingleLine(true);
      LinearLayout.LayoutParams line2Params = new LinearLayout.LayoutParams(-2, -2);
      line2Params.topMargin = dp(3);
      lines.addView(fcLine2[i], line2Params);
    }

    // ---- news column + tomorrow's dressing-guide panel ----
    // The panel takes the news region's right side (its right edge lines up with the
    // forecast cards'); the news column narrows to what is left and its titles truncate
    // at the panel's left edge instead of running under it.
    LinearLayout newsArea = new LinearLayout(context);
    newsArea.setOrientation(LinearLayout.HORIZONTAL);
    infoLayer.addView(newsArea, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout newsCol = new LinearLayout(context);
    newsCol.setOrientation(LinearLayout.VERTICAL);
    newsArea.addView(newsCol, new LinearLayout.LayoutParams(0, -1, 1));

    LinearLayout newsHead = new LinearLayout(context);
    newsHead.setOrientation(LinearLayout.HORIZONTAL);
    newsHead.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
    headParams.topMargin = dp(12);
    newsCol.addView(newsHead, headParams);
    TextView newsTitle = text("今日 AI 新闻", 21, 0xfff2f5ff);
    newsTitle.setTypeface(null, Typeface.BOLD);
    newsHead.addView(newsTitle);
    newsTag = text("示例 · 数据源待接入", 13, 0xffffd68a);
    newsTag.setPadding(dp(10), dp(3), dp(10), dp(3));
    GradientDrawable tagBg = new GradientDrawable();
    tagBg.setColor(0x22ffb547);
    tagBg.setCornerRadius(dp(12));
    tagBg.setStroke(dp(1), 0x55ffb547);
    newsTag.setBackground(tagBg);
    LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
    tagParams.leftMargin = dp(12);
    newsHead.addView(newsTag, tagParams);

    newsList = new LinearLayout(context);
    newsList.setOrientation(LinearLayout.VERTICAL);
    newsCol.addView(newsList, new LinearLayout.LayoutParams(-1, 0, 1));

    // The dressing panel's title is exactly 「明日五行穿衣」 — no date or ganzhi suffix;
    // its five tier rows are (re)built by renderDressPanel whenever the date rolls over.
    dressPanel = new LinearLayout(context);
    dressPanel.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable dressBg = new GradientDrawable();
    dressBg.setColor(0x2b141d33);
    dressBg.setCornerRadius(dp(14));
    dressBg.setStroke(dp(1), 0x40ffffff);
    dressPanel.setBackground(dressBg);
    dressPanel.setPadding(dp(DRESS_PAD_H_DP), dp(10), dp(DRESS_PAD_H_DP), dp(10));
    LinearLayout.LayoutParams dressParams = new LinearLayout.LayoutParams(dp(DRESS_W_DP), -2);
    dressParams.leftMargin = dp(16);
    dressParams.topMargin = dp(12);
    newsArea.addView(dressPanel, dressParams);
    TextView dressTitle = text(DRESS_TITLE, 20, 0xfff2f5ff);
    dressTitle.setTypeface(null, Typeface.BOLD);
    dressTitle.setGravity(Gravity.CENTER_VERTICAL);
    dressPanel.addView(dressTitle, new LinearLayout.LayoutParams(-1, dp(DRESS_TITLE_H_DP)));
    dressRows = new LinearLayout(context);
    dressRows.setOrientation(LinearLayout.VERTICAL);
    dressPanel.addView(dressRows);

    // ---- wake hint bar: its own full-width strip pinned to the very bottom, a sibling of
    // the info layer (not part of its flow), with an opaque band so it reads as a bar and
    // the news above can never run under it. The phrase follows the configured wake word;
    // the literal here is only the pre-bind placeholder. ----
    wakeBar = new LinearLayout(context);
    wakeBar.setOrientation(LinearLayout.HORIZONTAL);
    wakeBar.setGravity(Gravity.CENTER);
    wakeBar.setBackgroundColor(WAKE_BAR_BG);
    wakeBar.setVisibility(GONE);
    addView(
        wakeBar,
        new FrameLayout.LayoutParams(-1, dp(WAKE_BAR_H), Gravity.BOTTOM));
    wakeText = text("喊「小麦小麦」随时唤醒我", 17, 0xffb6c0e2);
    wakeBar.addView(wakeText, new LinearLayout.LayoutParams(-2, -2));

    // ---- photo layer ----
    photoLayer = new FrameLayout(context);
    photoLayer.setVisibility(GONE);
    addView(photoLayer, new FrameLayout.LayoutParams(-1, -1));
    photoA = photoImage(context);
    photoB = photoImage(context);
    photoLayer.addView(photoA, new FrameLayout.LayoutParams(-1, -1));
    photoLayer.addView(photoB, new FrameLayout.LayoutParams(-1, -1));
    photoEmpty = text("暂无照片\n添加本地照片后可在此轮播", 22, 0xffb6c0e2);
    photoEmpty.setGravity(Gravity.CENTER);
    photoEmpty.setVisibility(GONE);
    photoLayer.addView(photoEmpty, new FrameLayout.LayoutParams(-1, -1));
    photoClock = text("", 30, 0xffffffff);
    photoClock.setShadowLayer(dp(3), 0, dp(1), 0xaa000000);
    photoClock.setPadding(dp(28), 0, 0, dp(24));
    FrameLayout.LayoutParams clockParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
    photoLayer.addView(photoClock, clockParams);
  }

  private ImageView photoImage(Context context) {
    ImageView view = new ImageView(context);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    return view;
  }

  private float density() {
    return getResources().getDisplayMetrics().density;
  }

  private int dp(int n) {
    return Math.round(n * density());
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  /**
   * Wire the data source, the photo folder and the wake-phrase source. Called once from
   * MainActivity per buildUi. The wake phrase is read through the supplier every time the
   * info screen shows, so a settings change is picked up without rebinding.
   */
  void bind(AmbientRepository repository, File photosDirectory, Supplier<String> wakePhrase) {
    repo = repository;
    photosDir = photosDirectory;
    this.wakePhrase = wakePhrase;
  }

  private void refreshWakeText() {
    String phrase = wakePhrase == null ? null : wakePhrase.get();
    if (phrase == null || phrase.trim().isEmpty()) phrase = "小麦小麦";
    phrase = phrase.trim();
    // Only the wake phrase itself stands out (amber and bold); the rest of the hint keeps
    // the bar's plain colour and weight.
    String full = "喊「" + phrase + "」随时唤醒我";
    SpannableString span = new SpannableString(full);
    int start = full.indexOf(phrase);
    int end = start + phrase.length();
    span.setSpan(
        new ForegroundColorSpan(0xffffd68a), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    span.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    wakeText.setText(span);
  }

  boolean isShowing() {
    return getVisibility() == VISIBLE;
  }

  void show() {
    show(Mode.INFO);
  }

  void show(Mode selectedMode) {
    if (isShowing()) return;
    mode = selectedMode;
    boolean info = mode == Mode.INFO;
    bgLayer.setVisibility(info ? VISIBLE : GONE);
    photoLayer.setVisibility(info ? GONE : VISIBLE);
    infoLayer.setVisibility(info ? VISIBLE : GONE);
    wakeBar.setVisibility(info ? VISIBLE : GONE);
    setVisibility(VISIBLE);
    renderClock();
    ui.removeCallbacks(clockTick);
    ui.postDelayed(clockTick, 1000);
    ui.removeCallbacks(shiftTick);
    if (info) {
      refreshWakeText();
      ui.postDelayed(shiftTick, SHIFT_MS);
      startBackground();
      refreshData();
    } else {
      enterPhotoMode();
    }
  }

  void hide() {
    if (!isShowing()) return;
    setVisibility(GONE);
    ui.removeCallbacks(clockTick);
    ui.removeCallbacks(shiftTick);
    ui.removeCallbacks(photoTick);
    ui.removeCallbacks(bgTick);
    photoA.animate().cancel();
    photoB.animate().cancel();
    photoA.clearAnimation();
    photoB.clearAnimation();
    bgA.animate().cancel();
    bgB.animate().cancel();
    bgA.clearAnimation();
    bgB.clearAnimation();
  }

  @Override
  protected void onDetachedFromWindow() {
    hide();
    super.onDetachedFromWindow();
  }

  /**
   * Switch to the photo frame. Returns false and shows an empty-state hint when no photos exist.
   */
  boolean enterPhotoMode() {
    List<File> files = photosDir == null ? new ArrayList<>() : listPhotoFiles(photosDir);
    photoFiles = files;
    photoIndex = 0;
    mode = Mode.PHOTO;
    bgLayer.setVisibility(GONE);
    infoLayer.setVisibility(GONE);
    wakeBar.setVisibility(GONE);
    photoLayer.setVisibility(VISIBLE);
    ui.removeCallbacks(photoTick);
    photoEmpty.setVisibility(files.isEmpty() ? VISIBLE : GONE);
    photoA.setImageBitmap(null);
    photoB.setImageBitmap(null);
    if (frontBitmap != null) {
      frontBitmap.recycle();
      frontBitmap = null;
    }
    if (files.isEmpty()) return false;
    showPhoto(photoIndex);
    ui.postDelayed(photoTick, PHOTO_MS);
    return true;
  }

  private static String extensionOf(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** Usable images in the photos folder, in file-name order. Shared by frame and background. */
  private static List<File> listPhotoFiles(File dir) {
    File[] listed =
        dir.listFiles(f -> f.isFile() && PHOTO_EXTS.contains(extensionOf(f.getName())));
    List<File> files = new ArrayList<>();
    if (listed != null) Collections.addAll(files, listed);
    Collections.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
    return files;
  }

  // ---- info-screen background ----

  /**
   * Pick the background sources for this showing: the user's photos when the folder holds
   * any, otherwise the bundled defaults. Starts the slow rotation; the layer itself sits
   * under the scrim, so brightness never competes with the text above it.
   */
  private void startBackground() {
    bgFiles = photosDir == null ? new ArrayList<>() : listPhotoFiles(photosDir);
    bgAssetNames = bgFiles.isEmpty() ? listBundledBackgrounds() : new ArrayList<>();
    bgIndex = 0;
    ui.removeCallbacks(bgTick);
    if (bgCount() == 0) return;
    showBackground(0, false);
    ui.postDelayed(bgTick, BG_MS);
  }

  private int bgCount() {
    return !bgFiles.isEmpty() ? bgFiles.size() : bgAssetNames.size();
  }

  private List<String> listBundledBackgrounds() {
    List<String> names = new ArrayList<>();
    try {
      String[] listed = getContext().getAssets().list(BG_ASSET_DIR);
      if (listed != null) {
        for (String name : listed) {
          if (PHOTO_EXTS.contains(extensionOf(name))) names.add(name);
        }
      }
    } catch (IOException e) {
      // No bundled backgrounds readable: the solid colour behind the scrim stays.
    }
    Collections.sort(names);
    return names;
  }

  /** Same two-ImageView crossfade as the photo frame, minus the push-in animation. */
  private void showBackground(final int index, final boolean crossfade) {
    if (index < 0 || index >= bgCount()) return;
    new Thread(
            () -> {
              final Bitmap bitmap =
                  !bgFiles.isEmpty()
                      ? decodeSampled(bgFiles.get(index))
                      : decodeSampledAsset(BG_ASSET_DIR + "/" + bgAssetNames.get(index));
              if (bitmap == null) return;
              ui.post(
                  () -> {
                    if (!isShowing() || mode != Mode.INFO) {
                      bitmap.recycle();
                      return;
                    }
                    ImageView incoming = bgFrontIsA ? bgB : bgA;
                    final ImageView outgoing = bgFrontIsA ? bgA : bgB;
                    incoming.clearAnimation();
                    incoming.animate().cancel();
                    incoming.setImageBitmap(bitmap);
                    incoming.bringToFront();
                    bgScrim.bringToFront();
                    final Bitmap old = bgFrontBitmap;
                    if (crossfade) {
                      incoming.setAlpha(0f);
                      incoming
                          .animate()
                          .alpha(1f)
                          .setDuration(FADE_MS)
                          .withEndAction(
                              () -> {
                                outgoing.setImageBitmap(null);
                                if (old != null && old != bitmap) old.recycle();
                              })
                          .start();
                    } else {
                      incoming.setAlpha(1f);
                      outgoing.setImageBitmap(null);
                      if (old != null && old != bitmap) old.recycle();
                    }
                    bgFrontBitmap = bitmap;
                    bgFrontIsA = !bgFrontIsA;
                  });
            })
        .start();
  }

  // ---- clock / date ----

  private void renderClock() {
    Date now = new Date();
    String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
    timeText.setText(hm);
    photoClock.setText(hm);
    String lunar = LunarCalendar.format(LocalDate.now());
    String date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now);
    dateText.setText(lunar.isEmpty() ? date : date + " · " + lunar);
    renderDressPanel();
  }

  // ---- data ----

  private void refreshData() {
    if (repo == null) return;
    long now = System.currentTimeMillis();
    if (now - lastWeatherFetchMs >= WEATHER_TTL_MS) {
      lastWeatherFetchMs = now;
      repo.fetchWeather(
          weather -> {
            if (weather != null) renderWeather(weather);
            else renderWeatherUnavailable();
          });
    }
    if (now - lastNewsFetchMs > NEWS_TTL_MS) {
      lastNewsFetchMs = now;
      repo.fetchNews(this::renderNews);
    }
  }

  private void renderWeather(AmbientData.WeatherNow wx) {
    // The city name is whatever the hub reported for this reading — never a local constant.
    wxCity.setVisibility(wx.city.isEmpty() ? GONE : VISIBLE);
    wxCity.setText(wx.city);
    wxFallback.setVisibility(GONE);
    wxCluster.setVisibility(VISIBLE);

    String condition = wx.condition == null ? "" : wx.condition;
    wxIcon.setKind(AmbientData.classifyIcon(condition));
    float condSizePx = sp(WX_COND_SP);
    wxCondGlyph.setGlyphText(condition, condSizePx, 0xfff2f5ff);
    // The temperature's glyph height equals the icon column's full height — icon + gap +
    // the condition glyph's own height — with every part measured, never estimated: the
    // temperature's font size is scaled from a probe measurement to hit that target, so
    // its top aligns with the icon's top and its bottom with the condition's bottom.
    int targetPx =
        dp(WX_ICON_DP)
            + dp(WX_COND_GAP_DP)
            + Math.round(glyphHeightPx(condition, condSizePx, false));
    if (wx.currentTemp != null) {
      String tempText = wx.currentTemp + "°";
      float probePx = glyphHeightPx(tempText, 100f, true);
      float sizePx = probePx > 0 ? 100f * targetPx / probePx : 100f;
      wxTempGlyph.setGlyphText(tempText, sizePx, 0xfff2f5ff);
      wxTempGlyph.setVisibility(VISIBLE);
    } else {
      // No live temperature: no empty number — the rest of the cluster keeps its shape.
      wxTempGlyph.setVisibility(GONE);
    }
    setHeightPx(wxDivider, targetPx);
    setHeightPx(wxDetails, targetPx);
    alignClockBlock(!wx.city.isEmpty(), targetPx);

    // Detail rows: name fixed-width at the left, the value column sized to the widest
    // value actually measured, one small gap between them — name and value sit close
    // as one compact group. (A weighted value column inside a fixed 160 dp box
    // stretched each value to the right margin and left a wide empty gap beside the
    // names on the real panel.) The group as a whole stays flush to the right margin
    // via the cluster's END gravity, the divider hugging the name column's left; no
    // rows, no divider, cluster stays compact.
    wxDetails.removeAllViews();
    boolean hasDetails = wx.detailRows != null && !wx.detailRows.isEmpty();
    wxDetails.setVisibility(hasDetails ? VISIBLE : GONE);
    wxDivider.setVisibility(hasDetails ? VISIBLE : GONE);
    if (hasDetails) {
      float widestValue = 0f;
      for (AmbientData.DetailRow detail : wx.detailRows) {
        widestValue = Math.max(widestValue, textWidthPx(detail.value, sp(19), true));
      }
      int valueWPx = (int) Math.ceil(widestValue) + dp(2);
      int detailsWPx = dp(WX_DETAIL_NAME_W_DP) + dp(WX_DETAIL_GAP_DP) + valueWPx;
      ViewGroup.LayoutParams detailsLp = wxDetails.getLayoutParams();
      if (detailsLp.width != detailsWPx) {
        detailsLp.width = detailsWPx;
        wxDetails.setLayoutParams(detailsLp);
      }
      for (AmbientData.DetailRow detail : wx.detailRows) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wxDetails.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = text(detail.name, 19, 0xffb6c0e2);
        row.addView(name, new LinearLayout.LayoutParams(dp(WX_DETAIL_NAME_W_DP), -2));
        TextView value = text(detail.value, 19, 0xfff2f5ff);
        value.setTypeface(null, Typeface.BOLD);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(valueWPx, -2);
        valueParams.leftMargin = dp(WX_DETAIL_GAP_DP);
        row.addView(value, valueParams);
      }
    }
    renderForecast(wx.forecast);
  }

  /** The forecast strip: the hub's first three days, one card each, hidden when absent. */
  private void renderForecast(List<AmbientData.ForecastDay> days) {
    boolean any = days != null && !days.isEmpty();
    wxForecast.setVisibility(any ? VISIBLE : GONE);
    if (!any) return;
    for (int i = 0; i < fcCards.length; i++) {
      if (i >= days.size()) {
        fcCards[i].setVisibility(GONE);
        continue;
      }
      fcCards[i].setVisibility(VISIBLE);
      AmbientData.ForecastDay day = days.get(i);
      fcIcons[i].setKind(AmbientData.classifyIcon(day.text));
      String line1 =
          FORECAST_LABELS[i] + (day.text == null || day.text.isEmpty() ? "" : " " + day.text);
      String line2 = AmbientRepository.forecastRangeText(day.high, day.low);
      fcLine1[i].setText(line1);
      fcLine2[i].setText(line2);
      // The text column gets an exact width measured with a probe Paint: left as nested
      // wrap-content inside the weight-sized cards, its measured width came out short
      // and the condition's last character was clipped (多云 lost its 云）. Measured, it
      // always fits the string it has to hold, whatever the condition wording.
      int linesW =
          (int)
                  Math.ceil(
                      Math.max(
                          textWidthPx(line1, sp(19), true), textWidthPx(line2, sp(19), false)))
              + dp(2);
      ViewGroup.LayoutParams linesLp = fcLines[i].getLayoutParams();
      if (linesLp.width != linesW) {
        linesLp.width = linesW;
        fcLines[i].setLayoutParams(linesLp);
      }
    }
  }

  private void renderWeatherUnavailable() {
    wxCity.setVisibility(GONE);
    wxCity.setText("");
    wxCluster.setVisibility(GONE);
    wxForecast.setVisibility(GONE);
    wxFallback.setVisibility(VISIBLE);
  }

  // ---- tomorrow's five-element dressing guide ----

  /**
   * Rebuild the dressing panel when the date rolls over. Tomorrow's tiers are computed
   * from the date on the spot (see WuxingDress) — nothing is cached across days, so the
   * panel is always tomorrow's, never yesterday's leftovers.
   */
  private void renderDressPanel() {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    if (tomorrow.equals(dressDate)) return;
    dressDate = tomorrow;
    dressRows.removeAllViews();
    WuxingDress.Element[] tiers = WuxingDress.tiersFor(tomorrow);
    // Panel width follows its widest content, measured: the title with a probe Paint,
    // each tier row at its established pitch (the tier-name slot plus one fixed slot
    // per swatch) — plus the panel's own side padding. No fixed width with empty
    // room at the rows' right; the panel's right edge stays put and the weighted
    // news column on its left absorbs the freed width.
    int widestRowPx = 0;
    for (WuxingDress.Element tierElement : tiers) {
      int rowPx =
          dp(DRESS_TIER_W_DP) + WuxingDress.swatches(tierElement).length * dp(DRESS_SWATCH_W_DP);
      widestRowPx = Math.max(widestRowPx, rowPx);
    }
    int titlePx = (int) Math.ceil(textWidthPx(DRESS_TITLE, sp(20), true)) + dp(2);
    int panelWPx = Math.max(titlePx, widestRowPx) + 2 * dp(DRESS_PAD_H_DP);
    ViewGroup.LayoutParams panelLp = dressPanel.getLayoutParams();
    if (panelLp.width != panelWPx) {
      panelLp.width = panelWPx;
      dressPanel.setLayoutParams(panelLp);
    }
    for (int i = 0; i < WuxingDress.TIER_NAMES.length; i++) {
      LinearLayout row = new LinearLayout(getContext());
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      // Exact row height: the panel's total height is a known constant (see the budget
      // note at the geometry constants), so all five tiers fit by construction instead
      // of the last ones being squeezed into whatever height is left over.
      LinearLayout.LayoutParams rowParams =
          new LinearLayout.LayoutParams(-1, dp(DRESS_ROW_H_DP));
      rowParams.topMargin = dp(i == 0 ? 8 : 6);
      dressRows.addView(row, rowParams);
      TextView tier = text(WuxingDress.TIER_NAMES[i], 17, tierColor(i));
      if (i == 0) tier.setTypeface(null, Typeface.BOLD);
      row.addView(tier, new LinearLayout.LayoutParams(dp(DRESS_TIER_W_DP), -2));
      // Each colour sits in a fixed-pitch slot: dot + single-character name, white.
      for (WuxingDress.Swatch swatch : WuxingDress.swatches(tiers[i])) {
        LinearLayout slot = new LinearLayout(getContext());
        slot.setOrientation(LinearLayout.HORIZONTAL);
        slot.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(slot, new LinearLayout.LayoutParams(dp(DRESS_SWATCH_W_DP), -2));
        View dot = new View(getContext());
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(swatch.color);
        dotBg.setStroke(dp(1), 0x66ffffff);
        dot.setBackground(dotBg);
        slot.addView(dot, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView name = text(swatch.name, 16, 0xfff2f5ff);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        nameParams.leftMargin = dp(5);
        slot.addView(name, nameParams);
      }
    }
  }

  /** Tier-name styling from the mock: 大吉 amber bold, 忌用 soft red, the rest plain. */
  private static int tierColor(int tierIndex) {
    if (tierIndex == 0) return 0xffffd68a;
    if (tierIndex == WuxingDress.TIER_NAMES.length - 1) return 0xfff28b8b;
    return 0xfff2f5ff;
  }

  /** sp → px, matching TextView's own conversion, for glyph measurements. */
  private float sp(float size) {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, size, getResources().getDisplayMetrics());
  }

  /** Exact advance width of a string at a pixel text size, measured with a probe Paint. */
  private static float textWidthPx(String value, float sizePx, boolean bold) {
    if (value == null || value.isEmpty()) return 0f;
    Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
    probe.setTextSize(sizePx);
    probe.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    return probe.measureText(value);
  }

  /** Exact glyph height of a string at a pixel text size, measured with a probe Paint. */
  private static float glyphHeightPx(String value, float sizePx, boolean bold) {
    if (value == null || value.isEmpty()) return 0f;
    Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
    probe.setTextSize(sizePx);
    probe.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    Rect bounds = new Rect();
    probe.getTextBounds(value, 0, value.length(), bounds);
    return bounds.height();
  }

  /**
   * Lift the clock block until the date line's bottom lands exactly on the weather
   * cluster's bottom. The two columns share the top row's top anchor, but the clock
   * block is the taller of the two — its 96 sp line reserves the font's full
   * top..bottom — so the top row used to be as tall as the clock block and the whole
   * lower screen sat that much lower than the cluster it belongs under. The lift is
   * the measured difference between the two blocks (clock block height minus the
   * cluster bottom's offset within its own column: city line + cluster margin +
   * cluster body), never a guessed constant; with the block lifted, the top row
   * shrinks to the weather side's height and the forecast strip, news region and
   * dressing panel all move up by the same amount, the freed space collecting at
   * the bottom of the weighted news region, above the wake bar. Recomputed on every
   * weather render (the cluster body height arrives with it) and applied only when
   * it changes. The burn-in nudge translates the whole info layer, so the alignment
   * is against this unshifted base layout.
   */
  private void alignClockBlock(boolean cityShown, int clusterBodyPx) {
    int clockBlockPx = textLineHeightPx(96f, true) + dp(6) + textLineHeightPx(24f, false);
    int clusterBottomPx =
        (cityShown ? textLineHeightPx(20f, false) : 0) + dp(10) + clusterBodyPx;
    int shiftPx = clockBlockPx - clusterBottomPx;
    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) leftCol.getLayoutParams();
    int margin = shiftPx > 0 ? -shiftPx : 0;
    if (lp.topMargin != margin) {
      lp.topMargin = margin;
      leftCol.setLayoutParams(lp);
    }
  }

  /**
   * Height a plain single-line TextView reserves at this text size: the font's full
   * top..bottom, which is how TextView itself measures a line (font padding
   * included). Measured with a probe Paint so layout arithmetic uses the same
   * numbers the real views will.
   */
  private int textLineHeightPx(float sizeSp, boolean bold) {
    Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
    probe.setTextSize(sp(sizeSp));
    probe.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    Paint.FontMetricsInt fm = probe.getFontMetricsInt();
    return fm.bottom - fm.top;
  }

  private void setHeightPx(View view, int heightPx) {
    ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp != null && lp.height != heightPx) {
      lp.height = heightPx;
      view.setLayoutParams(lp);
    }
  }

  /**
   * A text view sized to its glyphs' exact bounds — no font ascent/descent padding — so a
   * line of text can be aligned edge-to-edge with neighbouring graphics: the cluster's
   * temperature spans the icon column exactly, and the condition text's own measured
   * height feeds that span. The glyphs are drawn at the view's origin.
   */
  private static final class GlyphTextView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private String glyphText = "";

    GlyphTextView(Context context, boolean bold) {
      super(context);
      paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    void setGlyphText(String value, float sizePx, int color) {
      glyphText = value == null ? "" : value;
      paint.setTextSize(sizePx);
      paint.setColor(color);
      paint.getTextBounds(glyphText, 0, glyphText.length(), bounds);
      requestLayout();
      invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      setMeasuredDimension(Math.max(1, bounds.width()), Math.max(1, bounds.height()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      if (!glyphText.isEmpty()) canvas.drawText(glyphText, -bounds.left, -bounds.top, paint);
    }
  }

  private void renderNews(AmbientData.NewsFeed feed) {
    newsTag.setVisibility(feed.fromHub ? GONE : VISIBLE);
    newsList.removeAllViews();
    int shown = 0;
    for (AmbientData.NewsItem item : feed.items) {
      if (shown >= MAX_NEWS_VISIBLE) break;
      shown++;
      LinearLayout row = new LinearLayout(getContext());
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      // Title-only rows — the hub feed's shape — get an exact height so four of them
      // are guaranteed to fit the news region; a row carrying a summary (only the
      // bundled placeholder feed has any) keeps wrap height.
      LinearLayout.LayoutParams rowParams =
          new LinearLayout.LayoutParams(-1, item.summary.isEmpty() ? dp(NEWS_ROW_H_DP) : -2);
      rowParams.topMargin = dp(8);
      newsList.addView(row, rowParams);

      TextView chip = text(item.category, 14, 0xff9edaff);
      chip.setPadding(dp(10), dp(3), dp(10), dp(3));
      GradientDrawable chipBg = new GradientDrawable();
      chipBg.setColor(0x149edaff);
      chipBg.setCornerRadius(dp(10));
      chipBg.setStroke(dp(1), 0x339edaff);
      chip.setBackground(chipBg);
      row.addView(chip, new LinearLayout.LayoutParams(-2, -2));

      LinearLayout texts = new LinearLayout(getContext());
      texts.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1);
      textsParams.leftMargin = dp(12);
      row.addView(texts, textsParams);
      // Titles live in the narrowed news column now: one line, end-ellipsis — the right
      // edge stops at the dressing panel instead of running the full screen width.
      TextView title = text(item.title, 20, 0xfff2f5ff);
      title.setMaxLines(1);
      title.setEllipsize(TextUtils.TruncateAt.END);
      texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
      if (!item.summary.isEmpty()) {
        TextView summary = text(item.summary, 15, 0xffb6c0e2);
        summary.setMaxLines(2);
        texts.addView(summary, new LinearLayout.LayoutParams(-1, -2));
      }
    }
  }

  // ---- photo frame ----

  private void showPhoto(int index) {
    if (index < 0 || index >= photoFiles.size()) return;
    final File file = photoFiles.get(index);
    new Thread(
            () -> {
              final Bitmap bitmap = decodeSampled(file);
              if (bitmap == null) return;
              ui.post(
                  () -> {
                    if (!isShowing() || mode != Mode.PHOTO) {
                      bitmap.recycle();
                      return;
                    }
                    ImageView incoming = frontIsA ? photoB : photoA;
                    final ImageView outgoing = frontIsA ? photoA : photoB;
                    incoming.clearAnimation();
                    incoming.animate().cancel();
                    incoming.setImageBitmap(bitmap);
                    incoming.setAlpha(0f);
                    incoming.setScaleX(1f);
                    incoming.setScaleY(1f);
                    incoming.bringToFront();
                    photoClock.bringToFront();
                    final Bitmap old = frontBitmap;
                    incoming
                        .animate()
                        .alpha(1f)
                        .setDuration(FADE_MS)
                        .withEndAction(
                            () -> {
                              outgoing.setImageBitmap(null);
                              if (old != null && old != bitmap) old.recycle();
                            })
                        .start();
                    // Slow push-in for the photo's whole slot, as a view animation so it
                    // does not fight the alpha animator; the Snapdragon 624 gets exactly
                    // this one cheap transform, nothing more.
                    ScaleAnimation pushIn =
                        new ScaleAnimation(
                            1f,
                            1.07f,
                            1f,
                            1.07f,
                            Animation.RELATIVE_TO_SELF,
                            0.5f,
                            Animation.RELATIVE_TO_SELF,
                            0.5f);
                    pushIn.setDuration(PHOTO_MS);
                    pushIn.setFillAfter(true);
                    incoming.startAnimation(pushIn);
                    frontBitmap = bitmap;
                    frontIsA = !frontIsA;
                  });
            })
        .start();
  }

  /** Decode at most ~1280 px wide: full-size photos would OOM this 2 GB device. */
  private static Bitmap decodeSampled(File file) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      if (bounds.outWidth <= 0) return null;
      int sample = 1;
      while (bounds.outWidth / sample > 1280) sample *= 2;
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inSampleSize = sample;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Asset twin of {@link #decodeSampled(File)}: bounds pass first, then a sampled decode. */
  private Bitmap decodeSampledAsset(String assetPath) {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      InputStream boundsIn = getContext().getAssets().open(assetPath);
      try {
        BitmapFactory.decodeStream(boundsIn, null, bounds);
      } finally {
        boundsIn.close();
      }
      if (bounds.outWidth <= 0) return null;
      int sample = 1;
      while (bounds.outWidth / sample > 1280) sample *= 2;
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inSampleSize = sample;
      InputStream in = getContext().getAssets().open(assetPath);
      try {
        return BitmapFactory.decodeStream(in, null, opts);
      } finally {
        in.close();
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  // Repository serves successful cache for 3 hours; check often enough for failure retries.
  private static final long WEATHER_TTL_MS = AmbientRepository.WEATHER_RETRY_INTERVAL_MS;
  private static final long NEWS_TTL_MS = 30 * 60_000L;
  private static final long SHIFT_MS = 60_000L;
  private static final long PHOTO_MS = 20_000L;
  private static final long BG_MS = 60_000L;
  private static final long FADE_MS = 700L;

  /** Height of the wake hint strip (dp); the info layer's bottom padding reserves it. */
  private static final int WAKE_BAR_H = 54;

  /** Near-opaque version of the screen's base colour: the strip reads as its own bar. */
  private static final int WAKE_BAR_BG = 0xe6050813;

  // Weather cluster + forecast strip geometry (the approved ref11 composition).
  private static final int WX_ICON_DP = 60;
  private static final int WX_COND_GAP_DP = 5;
  private static final float WX_COND_SP = 22f;
  /** Construction-time width only: render replaces it with the exact compact width. */
  private static final int WX_DETAILS_W_DP = 160;

  private static final int WX_DETAIL_NAME_W_DP = 52;

  /** Gap between a detail row's name and its value (dp). */
  private static final int WX_DETAIL_GAP_DP = 10;
  private static final int FORECAST_H_DP = 66;
  private static final String[] FORECAST_LABELS = {"今天", "明天", "后天"};

  // Vertical budget — recomputed as a whole when the forecast strip joined (the first
  // ref11 build never re-added it, and on the real panel the region below the strip
  // came up ~50 dp short: LinearLayout squeezed the overflow out of the dressing
  // panel's lower rows and the third news row). The screen is 800 px tall, ~533 dp at
  // the panel's density: top padding 20 + top row ~120 + forecast margin 12 +
  // strip 66 + bottom reserve 58 leaves ~255 dp for the news region. (The top row is
  // the weather side's height, not the clock block's ~146: the clock block is lifted
  // to bottom-align with the cluster — see alignClockBlock.) Against that, the
  // dressing panel is a known constant — padding 10+10, title 28, first row margin 8,
  // five rows of 24 with 6 dp gaps = 198, plus its 12 dp top margin = 210 — and the
  // news column needs 12 (header margin) + ~28 (header) + 4 × (8 + 32) = ~200. Both
  // fit with slack; exact heights keep it that way regardless of font metrics.
  /** News rows visible below the forecast strip. */
  private static final int MAX_NEWS_VISIBLE = 4;

  /** Exact height of a title-only news row (dp); see the budget note above. */
  private static final int NEWS_ROW_H_DP = 32;

  /** Dressing panel exact title and tier-row heights (dp); see the budget note above. */
  private static final int DRESS_TITLE_H_DP = 28;

  private static final int DRESS_ROW_H_DP = 24;

  /**
   * Dressing-guide panel width at construction; renderDressPanel replaces it with the
   * measured content width (268 dp at the pitch below: rows 46 + 3×62, padding 2×18;
   * the measured title is narrower and never governs).
   */
  private static final int DRESS_W_DP = 300;

  /** The dressing panel's title text — exactly this, no date or ganzhi suffix. */
  private static final String DRESS_TITLE = "明日五行穿衣";

  /** Dressing panel horizontal padding (dp), each side. */
  private static final int DRESS_PAD_H_DP = 18;

  /** Dressing tier-row pitch (dp): the tier-name slot, then one slot per swatch. */
  private static final int DRESS_TIER_W_DP = 46;

  private static final int DRESS_SWATCH_W_DP = 62;

  /** Bundled fallback backgrounds (the web prototype's four scenic photos) under assets/. */
  private static final String BG_ASSET_DIR = "ambient_bg";

  /** ~60% black over the background photo, so clock/weather/news text stays readable. */
  private static final int SCRIM_COLOR = 0x99000000;
  private static final List<String> PHOTO_EXTS =
      Arrays.asList("jpg", "jpeg", "png", "webp");

  /** Burn-in nudge cycle, in dp: small steps around the origin. */
  private static final float[][] SHIFTS = {
    {0, 0}, {4, 2}, {-3, 4}, {2, -3}, {-4, -2}, {0, 3}, {3, 0},
  };
}
