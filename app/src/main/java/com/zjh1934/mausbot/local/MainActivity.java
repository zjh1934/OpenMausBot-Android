package com.zjh1934.mausbot.local;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * MausBot 本地版外壳（第二版 · 产品级首启引导）。
 *
 * 界面完全由手机本机 127.0.0.1:&lt;port&gt; 上的 OpenMausBot 服务提供；服务未就绪时
 * 显示内置的产品级引导页（guide.html）：4 步清单 + 状态灯 + 一键授权，全程不出现代码。
 *
 * 设计要点（回应用户「这是给人用的，不是给人调试的」）：
 *  - 权限一律走系统授权框或 Shizuku 自动授权，不让用户翻设置页、不敲命令；
 *  - 借鉴 Shizuku 系「AI 操控手机」开源项目的引导范式：分步、状态灯、
 *    返回自动复检、全绿自动进入；
 *  - 首次运行环境准备受安卓平台限制，最少要在 Termux 里粘贴一次自包含命令：
 *    App 一键复制 + 打开 Termux，用户长按粘贴 + 回车即可；命令对用户完全不可见。
 *  - 一旦服务成功起来过，之后每次打开自动拉起，不再出现任何设置。
 *
 * 所有网络访问都指向手机本机回环，不访问公网（见 network_security_config.xml）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MausBotLocal";
    private static final String PREF = "mausbot_pref";
    private static final String KEY_PORT = "port";
    private static final String KEY_SKIP_SHIZUKU = "skip_shizuku";
    private static final String KEY_SKIP_BATTERY = "skip_battery";
    private static final String KEY_EVER_READY = "ever_ready";
    private static final String DEFAULT_PORT = "8799";

    private static final String GUIDE_URL = "file:///android_asset/guide.html";
    private static final String BOOTSTRAP_ASSET = "bootstrap.sh";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_SCRIPT = TERMUX_HOME + "/.openmausbot/start-omb.sh";
    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private static final int REQ_FILE = 1001;
    private static final int REQ_PERM = 1002;
    private static final int REQ_MEDIA = 1003;

    private WebView web;
    private ProgressBar progress;
    private ValueCallback<Uri[]> uploadCallback;
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private String port = DEFAULT_PORT;
    /** 完整的一次性自包含命令（base64 内嵌脚本），只在 Java 侧持有，从不展示给用户。 */
    private String fullBootstrapCmd = "";

    /** 服务是否就绪（health 通）。由后台探测更新，getStatus() 只读缓存，绝不做同步网络请求。 */
    private volatile boolean serviceUp = false;
    /** 引导页是否正在「忙」。 */
    private volatile boolean busyFlag = false;
    private volatile boolean showingGuide = true;

    private String baseUrl() { return "http://127.0.0.1:" + port + "/"; }
    private String healthUrl() { return baseUrl() + "api/health"; }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        port = prefs.getString(KEY_PORT, DEFAULT_PORT);
        if (port == null || port.isEmpty()) port = DEFAULT_PORT;

        fullBootstrapCmd = buildBootstrapCmd();

        buildUi();
        setupWebView();

        // 注册 Shizuku binder / 授权结果监听：连上后自动把 RUN_COMMAND 也授权好并刷新状态灯。
        ShizukuKit.init(() -> ui.post(this::onShizukuChanged));

        // 立即显示引导页（深色、不白屏），并启动持续探测：服务一起来就自动进入聊天。
        showGuide();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置 / 授权框 / Shizuku / Termux 返回：立即复检，状态灯自动打勾。
        if (showingGuide) {
            pushRefresh();
            startGuidePoll();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGuidePoll();  // 界面不可见时停探测，省电
    }

    @Override
    protected void onDestroy() {
        stopGuidePoll();
        ui.removeCallbacks(pasteFallback);
        pool.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Shizuku 13.x（v11+）授权结果走 listener，不需要 Activity 转发。
        // 这里只处理本 App 自己申请的 RUN_COMMAND / 音视频权限，回调后刷新状态灯。
        pushRefresh();
    }

    // ------------------------------------------------------------------ 界面

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E1420"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1420"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 5);
        lp.gravity = Gravity.TOP;
        root.addView(progress, lp);

        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // Web UI 的会话/设置依赖 localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);  // 语音朗读自动播放
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MausBotLocal/2.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new JsBridge(), "MausBot");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = u.getHost();
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                    // 外链交给系统浏览器，不在壳内跳走
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (url == null) return;
                if (url.startsWith(GUIDE_URL)) {
                    // 引导页加载完成主动刷新一次，让 4 个状态灯立刻反映真实状态。
                    v.evaluateJavascript("try{refresh()}catch(e){}", null);
                } else if (url.startsWith("http://127.0.0.1")) {
                    v.evaluateJavascript(BLOB_BRIDGE_JS, null);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                super.onReceivedError(v, req, err);
                if (req.isForMainFrame() && !showingGuide) {
                    ui.post(() -> {
                        showGuide();
                        ui.postDelayed(() -> pushNotice("连接中断，点「一键启动」重新拉起即可"), 450);
                    });
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (p < 100) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(p);
                } else {
                    progress.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "onShowFileChooser", e);
                    uploadCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                ui.post(() -> {
                    List<String> grant = new ArrayList<>();
                    boolean needAudio = false, needCamera = false;
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            grant.add(r); needAudio = true;
                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            grant.add(r); needCamera = true;
                        }
                    }
                    if (needAudio) ensurePermission(Manifest.permission.RECORD_AUDIO);
                    if (needCamera) ensurePermission(Manifest.permission.CAMERA);
                    // 只放行音视频捕获，其余（如地理位置、MIDI）一律拒绝
                    if (grant.isEmpty()) req.deny();
                    else req.grant(grant.toArray(new String[0]));
                });
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("确定", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("确定", (d, w) -> result.confirm())
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView v, String url, String msg, String def,
                                      final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(def == null ? "" : def);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setView(input)
                        .setPositiveButton("确定", (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, contentDisposition, mimeType));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || uploadCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            String dataString = data.getDataString();
            if (dataString != null) {
                results = new Uri[]{ Uri.parse(dataString) };
            } else if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
        }
        uploadCallback.onReceiveValue(results);
        uploadCallback = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && !showingGuide && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ 服务探测

    private void probeOnce(Consumer<Boolean> cb) {
        pool.execute(() -> {
            boolean ok = httpHealth();
            ui.post(() -> cb.accept(ok));
        });
    }

    /** 只要端口有 HTTP 应答（含 404）就说明服务进程活着，不依赖某个具体端点。 */
    private boolean httpHealth() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(healthUrl()).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 引导页期间的持续探测：服务一起来就自动进入聊天（用户不用点任何按钮）。
     * 同时周期性刷新状态灯，让「装好并启动」这一格自动打勾。
     * 只在界面可见时运行（onPause 停止），避免后台空转。
     */
    private final Runnable guidePoll = new Runnable() {
        @Override
        public void run() {
            if (!showingGuide) return;
            probeOnce(ok -> {
                if (!showingGuide) return;
                serviceUp = ok;
                if (ok) {
                    loadApp();
                } else {
                    pushRefresh();
                    ui.postDelayed(this, 2000);
                }
            });
        }
    };

    private void startGuidePoll() {
        stopGuidePoll();
        ui.post(guidePoll);
    }

    private void stopGuidePoll() {
        ui.removeCallbacks(guidePoll);
    }

    // ------------------------------------------------------------------ 页面切换 / 状态推送

    private void loadApp() {
        stopGuidePoll();
        ui.removeCallbacks(pasteFallback);
        busyFlag = false;
        serviceUp = true;
        showingGuide = false;
        // 记录「环境已就绪」，之后打开一律走全自动拉起，不再提示粘贴。
        prefs.edit().putBoolean(KEY_EVER_READY, true).apply();
        if (web != null) web.loadUrl(baseUrl());
    }

    private void showGuide() {
        showingGuide = true;
        if (web != null) web.loadUrl(GUIDE_URL);
        startGuidePoll();
    }

    /** 让引导页重新拉取一次状态并渲染（java -> js）。 */
    private void pushRefresh() {
        evalJs("try{refresh()}catch(e){}");
    }

    private void pushNotice(String text) {
        evalJs("try{setNotice(" + jsq(text) + ")}catch(e){}");
    }

    private void pushPasteGuide(boolean on) {
        // cmd 传空：粘贴卡片里绝不显示任何命令，主界面不出现代码。
        evalJs("try{setPasteGuide(" + on + ",'')}catch(e){}");
    }

    private void evalJs(String script) {
        if (web == null || !showingGuide) return;
        ui.post(() -> {
            if (web != null) web.evaluateJavascript(script, null);
        });
    }

    private static String jsq(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "") + "'";
    }

    /** 组装引导页状态对象。getStatus() 在 WebView 的 JS 线程调用，必须同步、快速、无网络。 */
    private String statusJson() {
        try {
            JSONObject s = new JSONObject();
            s.put("perm", termuxPermissionGranted());
            s.put("termuxInstalled", termuxInstalled());
            s.put("shizukuInstalled", ShizukuKit.installed(this));
            s.put("shizukuRunning", ShizukuKit.running());
            s.put("shizukuGranted", ShizukuKit.granted());
            s.put("shizukuSkipped", prefs.getBoolean(KEY_SKIP_SHIZUKU, false));
            s.put("rootMode", ShizukuKit.isRoot());
            s.put("battery", ignoringBattery());
            s.put("batterySkipped", prefs.getBoolean(KEY_SKIP_BATTERY, false));
            s.put("service", serviceUp);
            s.put("busy", busyFlag);
            s.put("port", port);
            // 高级折叠区只给一句人话；真实命令由 Java 内部持有，从不塞进界面。
            s.put("bootstrapCmd", "点上方「复制一次性命令」，或主卡片「复制并打开 Termux」");
            return s.toString();
        } catch (Exception e) {
            Log.w(TAG, "statusJson 失败", e);
            return "{}";
        }
    }

    // ------------------------------------------------------------------ 能力检测

    private boolean termuxPermissionGranted() {
        return checkSelfPermission(PERM_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean termuxInstalled() {
        try {
            getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ignoringBattery() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ------------------------------------------------------------------ 引导动作

    /** 第 1 步：允许自动启动。优先让 Shizuku 静默授权，失败才弹系统授权框。 */
    private void doAllowAutoStart() {
        if (termuxPermissionGranted()) {
            pushRefresh();
            return;
        }
        if (!termuxInstalled()) {
            pushNotice("没检测到 Termux，先装好 Termux 再回来点「允许」");
            openTermuxApp();
            return;
        }
        tryShizukuAutoGrant(() -> {
            if (!termuxPermissionGranted()) requestRunCommandPermission();
        });
    }

    private void requestRunCommandPermission() {
        try {
            requestPermissions(new String[]{ PERM_RUN_COMMAND }, REQ_PERM);
        } catch (Exception e) {
            Log.w(TAG, "requestPermissions 失败，回落设置页", e);
            openAppPermissionSettings();
        }
    }

    /** 用 Shizuku 的系统级身份静默授予 RUN_COMMAND，成功后（或失败后）执行 after，全程无损回落。 */
    private void tryShizukuAutoGrant(Runnable after) {
        pool.execute(() -> {
            boolean ok = false;
            if (ShizukuKit.granted()) {
                ok = ShizukuKit.grantRuntimePermission(getPackageName(), PERM_RUN_COMMAND);
            }
            Log.d(TAG, "Shizuku 自动授权 RUN_COMMAND: " + ok);
            ui.post(() -> {
                pushRefresh();
                if (after != null) after.run();
            });
        });
    }

    /** Shizuku binder / 授权结果变化：连上后自动把第 1 步权限也开好，并刷新状态灯。 */
    private void onShizukuChanged() {
        if (ShizukuKit.granted()) {
            tryShizukuAutoGrant(null);
        }
        pushRefresh();
    }

    /** 第 2 步：连一下 Shizuku。按「未装 / 未启动 / 未授权 / 已授权」四种状态分别处理。 */
    private void doConnectShizuku() {
        if (!ShizukuKit.installed(this)) {
            openShizukuDownload();
            toast("装好 Shizuku 后回来点「连接」；不想用可以点「跳过」");
            return;
        }
        if (!ShizukuKit.running()) {
            openShizukuApp();
            toast("在 Shizuku 里点「启动」，然后回来");
            return;
        }
        if (!ShizukuKit.granted()) {
            ShizukuKit.request(this);   // 结果走 ShizukuKit 的 listener -> onShizukuChanged
            return;
        }
        onShizukuChanged();
    }

    /** 第 3 步：保持后台运行。给本 App 一键加入电池优化白名单（系统弹框，点允许即可）。 */
    private void doKeepAlive() {
        if (ignoringBattery()) {
            pushRefresh();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("选「允许」。也记得把 Termux 的多任务卡片下拉锁定，别被系统清掉");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Log.w(TAG, "电池优化设置页打开失败（可忽略）", e2);
            }
        }
    }

    /** 第 4 步：一键启动。首次亮粘贴卡片；老用户全自动拉起 + 超时才回落。 */
    private void doPrepareAndStart() {
        if (serviceUp) {
            loadApp();
            return;
        }
        if (!termuxPermissionGranted()) {
            // 权限还没给，先补第 1 步，避免用户点了没反应。
            doAllowAutoStart();
            pushNotice("先完成第 1 步「允许自动启动」，再点一键启动");
            return;
        }

        boolean everReady = prefs.getBoolean(KEY_EVER_READY, false);
        busyFlag = true;
        pushRefresh();

        // 尽力拉起 Termux 里的服务。注意：首次 allow-external-apps 未开时，
        // Termux 会静默拒绝 RUN_COMMAND，startService 不一定抛异常，
        // 所以不能靠返回值判断成败，一律交给 guidePoll 探测结果。
        launchTermux();

        if (!everReady) {
            // 首次：立刻把一次性粘贴卡片亮出来，不让用户干等。
            busyFlag = false;
            pushPasteGuide(true);
            pushNotice("还差最后一次：按上面蓝色卡片，在 Termux 里粘贴一次就好，做完以后全自动");
            pushRefresh();
        } else {
            // 老用户：等 guidePoll 自动进入；20 秒仍没起来才回落显示粘贴卡片。
            ui.removeCallbacks(pasteFallback);
            ui.postDelayed(pasteFallback, 20000);
            pushNotice("正在自动启动服务，请稍候，会自动进入…");
        }
    }

    private final Runnable pasteFallback = () -> {
        if (showingGuide && !serviceUp) {
            busyFlag = false;
            pushPasteGuide(true);
            pushNotice("还没检测到服务：按上面蓝色卡片，在 Termux 里粘贴一次即可");
            pushRefresh();
        }
    };

    /** 通过 Termux 的 RUN_COMMAND 服务在本机后台执行启动脚本。 */
    private boolean launchTermux() {
        Intent i = termuxIntent();
        try {
            startService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startService(RUN_COMMAND) 失败，尝试前台服务方式", e);
            try {
                startForegroundService(i);
                return true;
            } catch (Exception e2) {
                Log.w(TAG, "startForegroundService 也失败（无损，交给探测）", e2);
                return false;
            }
        }
    }

    private Intent termuxIntent() {
        // 常量值已对照 Termux 官方 TermuxConstants.java 校准：
        //   ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        //   RunCommandService  = "com.termux.app.RunCommandService"
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_SCRIPT);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        return i;
    }

    // ------------------------------------------------------------------ 一次性命令 / 跳转

    /**
     * 把内置 bootstrap.sh 编码成一条自包含命令，用户粘贴回车即可，不依赖网络下载脚本。
     * 先 gzip 再 base64，命令从约 27KB 压到约 14KB，避免部分输入法 / Termux 截断长粘贴。
     * gzip 是 Termux 的 ESSENTIAL 基础包，必定存在；若压缩异常则退化为未压缩，命令仍自洽。
     */
    private String buildBootstrapCmd() {
        try (InputStream is = getAssets().open(BOOTSTRAP_ASSET);
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] raw = bo.toByteArray();
            // base64 字符集不含单引号与 %，用 printf 包裹绝对安全。
            byte[] gz = gzipOrNull(raw);
            if (gz != null) {
                String b64 = Base64.encodeToString(gz, Base64.NO_WRAP);
                return "printf %s '" + b64 + "' | base64 -d | gzip -dc | bash";
            }
            String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
            return "printf %s '" + b64 + "' | base64 -d | bash";
        } catch (Exception e) {
            Log.w(TAG, "读取内置 bootstrap.sh 失败", e);
            return "";
        }
    }

    /** 返回 null 表示压缩失败，由调用方退化为未压缩命令。 */
    private static byte[] gzipOrNull(byte[] src) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(bos);
            g.write(src);
            g.close();
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "gzip 失败，改用未压缩命令", e);
            return null;
        }
    }

    private void copyBootstrap(boolean openTermux) {
        if (fullBootstrapCmd.isEmpty()) {
            toast("准备命令生成失败，请重装本 App");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("MausBot 一次性准备命令", fullBootstrapCmd));
        if (openTermux) {
            openTermuxApp();
            toast("已复制并打开 Termux：在黑色窗口里 长按 → 粘贴 → 回车");
        } else {
            toast("已复制，粘贴到 Termux 回车即可");
        }
    }

    private void openTermuxApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.termux");
            if (i == null) throw new IllegalStateException("Termux 未安装");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.termux"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e2) {
                toast("没找到 Termux，请先安装 Termux（GitHub / F-Droid 版）");
            }
        }
    }

    private void openShizukuApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuKit.PKG);
            if (i == null) throw new IllegalStateException("Shizuku 未安装");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            openShizukuDownload();
        }
    }

    private void openShizukuDownload() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ShizukuKit.PKG));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
            } catch (Exception ignored) {}
        }
    }

    private void openAppPermissionSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ 下载 / 媒体权限

    private void handleDownload(String url, String contentDisposition, String mimeType) {
        String name = guessFileName(url, contentDisposition);
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mimeType);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", web.getSettings().getUserAgentString());
            dm.enqueue(r);
            Toast.makeText(this, "开始下载：" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "DownloadManager 失败，改用外部打开", e);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e2) {
                Toast.makeText(this, "无法下载文件", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static String guessFileName(String url, String cd) {
        String name = null;
        if (cd != null) {
            int i = cd.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                name = cd.substring(i + 9).replace("\"", "").split(";")[0].trim();
            }
        }
        if (name == null || name.isEmpty()) {
            String path = Uri.parse(url).getLastPathSegment();
            name = (path == null || path.isEmpty()) ? "mausbot-download" : path;
        }
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private void ensurePermission(String p) {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{p}, REQ_MEDIA); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ JS 桥

    private class JsBridge {

        /** 引导页状态数据源。返回 JSON 字符串，供 guide.html 的 refresh()/render() 消费。 */
        @JavascriptInterface
        public String getStatus() {
            return statusJson();
        }

        @JavascriptInterface
        public void allowAutoStart() { ui.post(MainActivity.this::doAllowAutoStart); }

        /** Termux 没装时，第 1 步 / 底部大按钮会调这个：引导去装 GitHub / F-Droid 版。 */
        @JavascriptInterface
        public void installTermux() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/termux/termux-app/releases"));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    openTermuxApp();
                }
                toast("装好 Termux（GitHub / F-Droid 版）后回来，会自动打勾");
            });
        }

        @JavascriptInterface
        public void connectShizuku() { ui.post(MainActivity.this::doConnectShizuku); }

        @JavascriptInterface
        public void skipShizuku() {
            prefs.edit().putBoolean(KEY_SKIP_SHIZUKU, true).apply();
            pushRefresh();
        }

        @JavascriptInterface
        public void keepAlive() { ui.post(MainActivity.this::doKeepAlive); }

        @JavascriptInterface
        public void skipBattery() {
            prefs.edit().putBoolean(KEY_SKIP_BATTERY, true).apply();
            pushRefresh();
        }

        @JavascriptInterface
        public void prepareAndStart() { ui.post(MainActivity.this::doPrepareAndStart); }

        /** 粘贴卡片主按钮：复制自包含命令 + 打开 Termux。 */
        @JavascriptInterface
        public void copyAndOpenTermux() { ui.post(() -> MainActivity.this.copyBootstrap(true)); }

        @JavascriptInterface
        public void copyBootstrap() { ui.post(() -> MainActivity.this.copyBootstrap(false)); }

        @JavascriptInterface
        public void openTermux() { ui.post(MainActivity.this::openTermuxApp); }

        @JavascriptInterface
        public void openPermissionSettings() { ui.post(MainActivity.this::openAppPermissionSettings); }

        @JavascriptInterface
        public void recheck() {
            ui.post(() -> {
                pushNotice("正在检测…");
                probeOnce(ok -> {
                    serviceUp = ok;
                    if (ok) loadApp();
                    else {
                        pushNotice("还没检测到服务，点「一键启动」试试");
                        pushRefresh();
                    }
                });
            });
        }

        @JavascriptInterface
        public void setPort(String p) {
            final String np = p == null ? "" : p.trim();
            int v;
            try { v = Integer.parseInt(np); } catch (Exception e) { v = -1; }
            if (v < 1 || v > 65535) {
                ui.post(() -> Toast.makeText(MainActivity.this,
                        "端口需为 1–65535 的数字", Toast.LENGTH_SHORT).show());
                return;
            }
            ui.post(() -> {
                port = np;
                prefs.edit().putString(KEY_PORT, np).apply();
                toast("端口已设为 " + np + "，正在重连");
                probeOnce(ok -> {
                    serviceUp = ok;
                    if (ok) loadApp();
                    else {
                        pushNotice("端口 " + np + " 上没有服务，请确认 Termux 里用了相同端口");
                        pushRefresh();
                    }
                });
            });
        }

        /** 供注入脚本调用：保存 Web UI 里以 blob/data URL 导出的文件。 */
        @JavascriptInterface
        public void saveFile(String name, String base64) {
            ui.post(() -> saveBase64ToFile(name, base64));
        }
    }

    private void saveBase64ToFile(String name, String base64) {
        try {
            byte[] data = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
            String safe = (name == null || name.isEmpty() ? "mausbot-export" : name)
                    .replaceAll("[/\\\\:*?\"<>|]", "_");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                cv.put(MediaStore.Downloads.MIME_TYPE, guessMime(safe));
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver cr = getContentResolver();
                Uri out = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (out == null) throw new IllegalStateException("MediaStore insert 返回空");
                try (OutputStream os = cr.openOutputStream(out)) {
                    if (os == null) throw new IllegalStateException("无法打开输出流");
                    os.write(data);
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(out, cv, null, null);
                Toast.makeText(this, "已保存到下载目录：" + safe, Toast.LENGTH_LONG).show();
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, safe);
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                Toast.makeText(this, "已保存：" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "saveBase64ToFile", e);
            Toast.makeText(this, "保存文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Web UI 常用 blob:/data: URL + a.download 导出文件，WebView 默认不会走 DownloadListener。
     * 这里在页面加载完成后注入一段脚本，把这类导出转成 base64 交给 Java 保存。
     */
    private static final String BLOB_BRIDGE_JS = "(function(){" +
            "if(window.__mausbotBlobHooked)return;window.__mausbotBlobHooked=true;" +
            "var orig=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){" +
            "try{" +
            "var h=this.href||'';" +
            "if(this.download&&(h.indexOf('blob:')===0||h.indexOf('data:')===0)){" +
            "var name=this.download||'mausbot-export',self=this;" +
            "var done=function(b64){if(window.MausBot&&MausBot.saveFile)MausBot.saveFile(name,b64)};" +
            "if(h.indexOf('data:')===0){done(h.split(',')[1]||'');return;}" +
            "fetch(h).then(function(r){return r.blob()}).then(function(b){" +
            "var fr=new FileReader();" +
            "fr.onloadend=function(){done(String(fr.result).split(',')[1]||'')};" +
            "fr.readAsDataURL(b)" +
            "}).catch(function(){orig.call(self)});" +
            "return;}" +
            "}catch(e){}" +
            "return orig.apply(this,arguments)" +
            "};})();";
}
