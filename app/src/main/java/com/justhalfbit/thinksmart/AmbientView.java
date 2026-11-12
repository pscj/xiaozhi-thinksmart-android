package com.justhalfbit.thinksmart;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
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
  private final TextView timeText, dateText, wxCity, wxTemp, wxCond, wxRange, wxMeta, newsTag,
      photoClock;
  private final View wxDivider;
  private final LinearLayout newsList;
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
    infoLayer.setPadding(dp(48), dp(36), dp(48), dp(WAKE_BAR_H + 8));
    addView(infoLayer, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout top = new LinearLayout(context);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.setGravity(Gravity.TOP);
    infoLayer.addView(top, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout left = new LinearLayout(context);
    left.setOrientation(LinearLayout.VERTICAL);
    top.addView(left, new LinearLayout.LayoutParams(0, -2, 1.2f));
    timeText = text("", 96, 0xfff2f5ff);
    timeText.setTypeface(null, Typeface.BOLD);
    left.addView(timeText);
    dateText = text("", 24, 0xffb6c0e2);
    LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-2, -2);
    dateParams.topMargin = dp(6);
    left.addView(dateText, dateParams);

    LinearLayout right = new LinearLayout(context);
    right.setOrientation(LinearLayout.VERTICAL);
    right.setGravity(Gravity.END);
    top.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
    wxCity = text("", 20, 0xffb6c0e2);
    wxCity.setGravity(Gravity.END);
    right.addView(wxCity);
    // Headline row (the approved mock's composition): the live temperature huge at
    // the left, a hairline divider, then a small stack — condition over today's
    // range. Temperature and stack are bottom-aligned, so the number's base sits
    // level with the range line. The city above and the detail line below stay
    // their own rows; nothing outside this block is touched.
    LinearLayout wxHead = new LinearLayout(context);
    wxHead.setOrientation(LinearLayout.HORIZONTAL);
    right.addView(wxHead, new LinearLayout.LayoutParams(-2, -2));
    wxTemp = text("", 80, 0xfff2f5ff);
    wxTemp.setTypeface(null, Typeface.BOLD);
    LinearLayout.LayoutParams tempParams = new LinearLayout.LayoutParams(-2, -2);
    tempParams.gravity = Gravity.BOTTOM;
    wxHead.addView(wxTemp, tempParams);
    wxDivider = new View(context);
    wxDivider.setBackgroundColor(0x59ffffff);
    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(64));
    dividerParams.leftMargin = dp(10);
    dividerParams.rightMargin = dp(10);
    dividerParams.gravity = Gravity.BOTTOM;
    wxHead.addView(wxDivider, dividerParams);
    LinearLayout wxStack = new LinearLayout(context);
    wxStack.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams stackParams = new LinearLayout.LayoutParams(-2, -2);
    stackParams.gravity = Gravity.BOTTOM;
    wxHead.addView(wxStack, stackParams);
    wxCond = text("", 30, 0xfff2f5ff);
    wxStack.addView(wxCond);
    wxRange = text("", 26, 0xffb6c0e2);
    LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(-2, -2);
    rangeParams.topMargin = dp(2);
    wxStack.addView(wxRange, rangeParams);
    wxMeta = text("", 16, 0xffb6c0e2);
    wxMeta.setGravity(Gravity.END);
    // The meta line must stay inside the weather column: full column width, one line,
    // end-ellipsis. Wrap-content width let a long line spill left across the screen.
    wxMeta.setMaxLines(1);
    wxMeta.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
    metaParams.topMargin = dp(14);
    right.addView(wxMeta, metaParams);

    // ---- news ----
    LinearLayout newsHead = new LinearLayout(context);
    newsHead.setOrientation(LinearLayout.HORIZONTAL);
    newsHead.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
    headParams.topMargin = dp(34);
    infoLayer.addView(newsHead, headParams);
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
    infoLayer.addView(newsList, new LinearLayout.LayoutParams(-1, 0, 1));

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
    // Headline row: with a live temperature, the big number and the divider show and
    // the stack carries condition over today's range (10° | 霾 / 1° ~ 15°). Without
    // one, only the stack shows — no empty number, no divider.
    boolean hasTemp = wx.currentTemp != null;
    wxTemp.setVisibility(hasTemp ? VISIBLE : GONE);
    wxTemp.setText(hasTemp ? wx.currentTemp + "°" : "");
    wxDivider.setVisibility(hasTemp ? VISIBLE : GONE);
    wxCond.setText(wx.condition);
    wxRange.setText(wx.lowTemp + "° ~ " + wx.highTemp + "°");
    // The hub payload has no AQI, so none is shown. The meta line is only a short slice
    // of the current-conditions detail — no provider name, no fetch timestamp (the user
    // found both noisy on the real screen).
    List<String> parts = new ArrayList<>();
    String detail = detailHead(wx.detail);
    if (!detail.isEmpty()) parts.add(detail);
    wxMeta.setText(join(parts));
  }

  /** First clause of the detail text, hard-capped: the meta line stays one short line. */
  private static String detailHead(String detail) {
    if (detail == null) return "";
    String head = detail.trim();
    int cut = head.length();
    for (char sep : new char[] {'。', '；', ';', '\n'}) {
      int at = head.indexOf(sep);
      if (at >= 0 && at < cut) cut = at;
    }
    head = head.substring(0, cut).trim();
    if (head.length() > 36) head = head.substring(0, 36) + "…";
    return head;
  }

  private void renderWeatherUnavailable() {
    wxCity.setVisibility(GONE);
    wxCity.setText("");
    wxTemp.setVisibility(GONE);
    wxTemp.setText("");
    wxDivider.setVisibility(GONE);
    wxCond.setText("天气暂不可用");
    wxRange.setText("");
    wxMeta.setText("");
  }

  private void renderNews(AmbientData.NewsFeed feed) {
    newsTag.setVisibility(feed.fromHub ? GONE : VISIBLE);
    newsList.removeAllViews();
    for (AmbientData.NewsItem item : feed.items) {
      LinearLayout row = new LinearLayout(getContext());
      row.setOrientation(LinearLayout.HORIZONTAL);
      LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
      rowParams.topMargin = dp(14);
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
      // Match-parent widths: the text column spans the full row (通栏), titles and summaries
      // wrap at the row's full width instead of a wrap-content measure.
      texts.addView(text(item.title, 20, 0xfff2f5ff), new LinearLayout.LayoutParams(-1, -2));
      if (!item.summary.isEmpty()) {
        TextView summary = text(item.summary, 15, 0xffb6c0e2);
        summary.setMaxLines(2);
        texts.addView(summary, new LinearLayout.LayoutParams(-1, -2));
      }
    }
  }

  private static String join(List<String> parts) {
    StringBuilder out = new StringBuilder();
    for (String part : parts) {
      if (out.length() > 0) out.append("  ·  ");
      out.append(part);
    }
    return out.toString();
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
