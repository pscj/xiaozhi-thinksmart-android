package com.justhalfbit.thinksmart;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import okhttp3.*;
import org.json.*;

public class MainActivity extends Activity implements VoiceService.Listener {
  private VoiceService service;
  private boolean bound, visible;
  private TextView status, history, warning;
  private EditText input;
  private ScrollView scroll;
  private Orb orb;
  private SharedPreferences prefs;
  private final Handler ui = new Handler();
  private final OkHttpClient http = new OkHttpClient();
  private final ServiceConnection connection =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
          service = ((VoiceService.LocalBinder) b).service();
          bound = true;
          service.attach(MainActivity.this);
        }

        public void onServiceDisconnected(ComponentName n) {
          bound = false;
          service = null;
          status.setText("服务已停止");
        }
      };
  private final Runnable brightnessTick =
      new Runnable() {
        public void run() {
          if (visible) {
            applyBrightness();
            ui.postDelayed(this, 30000);
          }
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("config", MODE_PRIVATE);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    buildUi();
  }

  private int dp(int x) {
    return Math.round(x * getResources().getDisplayMetrics().density);
  }

  private TextView label(String text, int size) {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(size);
    t.setTextColor(Color.rgb(225, 236, 249));
    return t;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(12), dp(20), dp(8));
    root.setBackgroundColor(Color.rgb(12, 19, 33));
    setContentView(root);
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label("小麦 · ThinkSmart", 22);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(button("设置", this::settings));
    top.addView(
        button(
            "停止服务",
            () -> {
              stopVoice();
            }));
    root.addView(top);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(
        getResources().getConfiguration().orientation == 2
            ? LinearLayout.HORIZONTAL
            : LinearLayout.VERTICAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout left = new LinearLayout(this);
    left.setOrientation(LinearLayout.VERTICAL);
    left.setGravity(Gravity.CENTER);
    orb = new Orb();
    left.addView(orb, new LinearLayout.LayoutParams(dp(150), dp(150)));
    status = label("待机", 23);
    status.setGravity(Gravity.CENTER);
    left.addView(status);
    TextView hint = label("小麦小麦 / 哈喽小麦 / 小麦同学", 13);
    hint.setGravity(Gravity.CENTER);
    left.addView(hint);
    left.addView(button("点击唤醒 / 打断", () -> withService(() -> service.wake())));
    Button ptt = button("按住说话", () -> {});
    ptt.setOnTouchListener(
        (v, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN) {
            withService(() -> service.ptt(true));
            return true;
          }
          if (e.getAction() == MotionEvent.ACTION_UP
              || e.getAction() == MotionEvent.ACTION_CANCEL) {
            if (service != null) service.ptt(false);
            if (e.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
          }
          return true;
        });
    left.addView(ptt);
    left.addView(
        button(
            "结束对话",
            () -> {
              if (service != null) service.end();
            }));
    boolean landscape = getResources().getConfiguration().orientation == 2;
    body.addView(
        left,
        landscape
            ? new LinearLayout.LayoutParams(dp(270), -1)
            : new LinearLayout.LayoutParams(-1, -2));
    scroll = new ScrollView(this);
    history = label("填写网关地址后开始对话", 18);
    history.setTextIsSelectable(true);
    history.setPadding(dp(16), dp(12), dp(8), dp(12));
    scroll.addView(history);
    body.addView(
        scroll,
        landscape
            ? new LinearLayout.LayoutParams(0, -1, 1)
            : new LinearLayout.LayoutParams(-1, 0, 1));
    warning = label("", 13);
    warning.setTextColor(Color.rgb(255, 194, 123));
    root.addView(warning);
    LinearLayout bottom = new LinearLayout(this);
    input = new EditText(this);
    input.setTextColor(Color.WHITE);
    input.setHintTextColor(Color.GRAY);
    input.setHint("输入文字问题");
    input.setSingleLine(true);
    bottom.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    bottom.addView(
        button(
            "发送",
            () -> {
              String t = input.getText().toString();
              if (!t.trim().isEmpty())
                withService(
                    () -> {
                      service.text(t);
                      input.setText("");
                    });
            }));
    root.addView(bottom);
  }

  private void withService(Runnable action) {
    if (service != null) action.run();
    else {
      startVoice();
      Toast.makeText(this, "正在启动，请稍后再操作", Toast.LENGTH_SHORT).show();
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
    startForegroundService(new Intent(this, VoiceService.class));
    if (!bound) bindService(new Intent(this, VoiceService.class), connection, BIND_AUTO_CREATE);
  }

  private void stopVoice() {
    if (bound) {
      if (service != null) service.detach();
      unbindService(connection);
      bound = false;
    }
    service = null;
    stopService(new Intent(this, VoiceService.class));
    status.setText("服务已停止");
    warning.setText("点击唤醒可重新启动");
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    startVoice();
    ui.post(brightnessTick);
  }

  @Override
  protected void onStop() {
    visible = false;
    ui.removeCallbacks(brightnessTick);
    if (bound) {
      if (service != null) service.detach();
      unbindService(connection);
      bound = false;
    }
    service = null;
    super.onStop();
  }

  @Override
  public void onRequestPermissionsResult(int r, String[] p, int[] grants) {
    super.onRequestPermissionsResult(r, p, grants);
    if (r == 10) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startVoice();
      else warning.setText("需要麦克风权限才能进行语音对话");
    }
  }

  public void update(String state, String text, String error) {
    if (state.equals("服务已停止")) {
      stopVoice();
      return;
    }
    status.setText(state);
    orb.active = !state.equals("待机") && !state.equals("未连接");
    orb.invalidate();
    if (!history.getText().toString().equals(text)) {
      history.setText(text);
      scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    warning.setText(error);
    applyBrightness();
  }

  private void applyBrightness() {
    WindowManager.LayoutParams p = getWindow().getAttributes();
    p.screenBrightness =
        (service != null && service.sleeping()
                ? 10
                : Protocol.percent(prefs.getInt("brightness", 80)))
            / 100f;
    p.screenBrightness = Math.max(.02f, p.screenBrightness);
    getWindow().setAttributes(p);
    if (prefs.getBoolean("keepScreen", true))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private EditText field(LinearLayout form, String title, String value, boolean secret) {
    form.addView(label(title, 15));
    EditText e = new EditText(this);
    e.setTextColor(Color.WHITE);
    e.setText(value);
    e.setSingleLine(true);
    e.setInputType(
        secret
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    form.addView(e);
    return e;
  }

  private CheckBox check(LinearLayout form, String text, boolean value) {
    CheckBox c = new CheckBox(this);
    c.setText(text);
    c.setTextColor(Color.WHITE);
    c.setChecked(value);
    form.addView(c);
    return c;
  }

  private void settings() {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), dp(8), dp(20), dp(8));
    EditText ws =
        field(
            form,
            "网关 WebSocket，例如 ws://192.168.1.10:16689/xiaozhi/v1/",
            prefs.getString("ws", ""),
            false);
    EditText token = field(form, "Token（与网关配置一致）", prefs.getString("token", ""), true);
    EditText admin = field(form, "可选：管理 HTTP 地址（需网关允许此设备访问）", prefs.getString("http", ""), false);
    EditText device = field(form, "设备 ID（已自动生成）", prefs.getString("device", ""), false);
    CheckBox wake = check(form, "启用离线唤醒词", prefs.getBoolean("wake", true)),
        keep = check(form, "保持屏幕常亮", prefs.getBoolean("keepScreen", true));
    form.addView(label("应用内屏幕亮度", 15));
    SeekBar bright = new SeekBar(this);
    bright.setMax(100);
    bright.setProgress(prefs.getInt("brightness", 80));
    form.addView(bright);
    CheckBox sleep = check(form, "启用睡眠时段（应用屏幕调暗）", prefs.getBoolean("sleep", false));
    EditText start = field(form, "开始 HH:MM", prefs.getString("sleepStart", "22:00"), false),
        end = field(form, "结束 HH:MM", prefs.getString("sleepEnd", "07:00"), false);
    form.addView(button("切换服务器模型", this::models));
    form.addView(
        button(
            "清除本机显示记录",
            () -> {
              if (service != null) service.clearLocal();
            }));
    ScrollView box = new ScrollView(this);
    box.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("小麦设置")
            .setView(box)
            .setPositiveButton("保存并重连", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        String url = ws.getText().toString().trim();
                        if (!url.startsWith("ws://") && !url.startsWith("wss://"))
                          throw new IllegalArgumentException("请输入 ws:// 或 wss:// 地址");
                        if (HttpUrl.parse(url.replaceFirst("^ws", "http")) == null)
                          throw new IllegalArgumentException("WebSocket 地址无效");
                        Protocol.minutes(start.getText().toString());
                        Protocol.minutes(end.getText().toString());
                        String httpUrl = admin.getText().toString().trim();
                        if (!httpUrl.isEmpty() && HttpUrl.parse(httpUrl) == null)
                          throw new IllegalArgumentException("HTTP 管理地址无效");
                        String deviceId = device.getText().toString().trim();
                        if (deviceId.isEmpty()) throw new IllegalArgumentException("设备 ID 不能为空");
                        prefs
                            .edit()
                            .putString("ws", url)
                            .putString("token", token.getText().toString())
                            .putString("http", httpUrl.replaceAll("/+$", ""))
                            .putString("device", deviceId)
                            .putBoolean("wake", wake.isChecked())
                            .putBoolean("keepScreen", keep.isChecked())
                            .putInt("brightness", bright.getProgress())
                            .putBoolean("sleep", sleep.isChecked())
                            .putString("sleepStart", start.getText().toString())
                            .putString("sleepEnd", end.getText().toString())
                            .apply();
                        if (service != null) service.reconnect();
                        applyBrightness();
                        dialog.dismiss();
                      } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                      }
                    }));
    dialog.show();
  }

  private void models() {
    String base = prefs.getString("http", "");
    if (base.isEmpty()) {
      Toast.makeText(this, "先保存管理 HTTP 地址", Toast.LENGTH_LONG).show();
      return;
    }
    String url =
        base + "/kiosk/llm?device=" + android.net.Uri.encode(prefs.getString("device", ""));
    request(
        new Request.Builder().url(url).build(),
        data -> {
          JSONArray options = data.optJSONArray("options");
          if (options == null || options.length() == 0) {
            warning.setText("网关未返回模型列表");
            return;
          }
          String[] names = new String[options.length()];
          for (int i = 0; i < names.length; i++) {
            JSONObject o = options.optJSONObject(i);
            names[i] = o.optString("label", o.optString("entry", o.optString("kind")));
          }
          new AlertDialog.Builder(this)
              .setTitle("选择模型")
              .setItems(
                  names,
                  (d, index) -> {
                    JSONObject o = options.optJSONObject(index);
                    JSONObject body =
                        VoiceService.json(
                            "device",
                            prefs.getString("device", ""),
                            "kind",
                            o.optString("kind"),
                            "vendor",
                            o.optString("vendor"),
                            "entry",
                            o.optString("entry"));
                    if (service != null) service.end();
                    request(
                        new Request.Builder()
                            .url(base + "/kiosk/llm/use")
                            .post(
                                RequestBody.create(
                                    MediaType.parse("application/json"), body.toString()))
                            .build(),
                        result -> {
                          if (result.optBoolean("ok") && service != null)
                            service.selectThread(o.optString("entry", "default"));
                          warning.setText(
                              result.optBoolean("ok")
                                  ? "模型已切换"
                                  : result.optString("error", "切换失败"));
                        });
                  })
              .show();
        });
  }

  private interface JsonResult {
    void receive(JSONObject data);
  }

  private void request(Request r, JsonResult action) {
    http.newCall(r)
        .enqueue(
            new Callback() {
              public void onFailure(Call c, IOException e) {
                runOnUiThread(
                    () -> {
                      if (visible) warning.setText("管理接口连接失败；语音仍可独立使用");
                    });
              }

              public void onResponse(Call c, Response r) {
                try (Response response = r) {
                  if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                  JSONObject data = new JSONObject(response.body().string());
                  runOnUiThread(
                      () -> {
                        if (visible) action.receive(data);
                      });
                } catch (Exception e) {
                  runOnUiThread(
                      () -> {
                        if (visible) warning.setText("管理接口不可用：" + e.getMessage());
                      });
                }
              }
            });
  }

  private final class Orb extends View {
    boolean active;
    Paint paint = new Paint(3);

    Orb() {
      super(MainActivity.this);
    }

    protected void onDraw(Canvas c) {
      float x = getWidth() / 2f, y = getHeight() / 2f, r = Math.min(x, y) * .82f;
      paint.setShader(
          new RadialGradient(
              x - r * .3f,
              y - r * .4f,
              r * 1.6f,
              new int[] {
                Color.rgb(180, 245, 255),
                active ? Color.rgb(48, 185, 233) : Color.rgb(85, 109, 223),
                Color.rgb(34, 29, 85)
              },
              null,
              Shader.TileMode.CLAMP));
      c.drawCircle(x, y, r, paint);
      paint.setShader(null);
      paint.setColor(Color.rgb(12, 28, 54));
      c.drawCircle(x - r * .27f, y - r * .07f, r * .065f, paint);
      c.drawCircle(x + r * .27f, y - r * .07f, r * .065f, paint);
      paint.setStrokeWidth(dp(3));
      paint.setStrokeCap(Paint.Cap.ROUND);
      c.drawLine(x - r * .13f, y + r * .25f, x + r * .13f, y + r * .25f, paint);
    }
  }
}
