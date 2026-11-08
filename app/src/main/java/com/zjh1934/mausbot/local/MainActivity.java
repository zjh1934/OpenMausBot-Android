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
import android.provider.MediaStore;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MausBot 本地版外壳。
 *
 * 界面完全由手机本机 127.0.0.1:&lt;port&gt; 上的 OpenMausBot 服务提供，
 * 本类只负责：探测服务、在没起来时显示引导页、一键拉起 Termux 里的服务、
 * 以及把 WebView 在 Android 上需要的能力补齐（文件选择、下载、权限、返回键）。
 *
 * 所有网络访问都指向手机本机回环，不访问公网（见 network_security_config.xml）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MausBotLocal";
    private static final String PREF = "mausbot_pref";
    private static final String KEY_PORT = "port";
    private static final String DEFAULT_PORT = "8799";
    private static final String GUIDE_URL = "file:///android_asset/guide.html";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_SCRIPT = TERMUX_HOME + "/.openmausbot/start-omb.sh";
    private static final String MANUAL_CMD = "sh " + TERMUX_SCRIPT;

    private static final int REQ_FILE = 1001;
    private static final int REQ_PERM = 1002;

    private WebView web;
    private ProgressBar progress;
    private ValueCallback<Uri[]> uploadCallback;
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicBoolean probing = new AtomicBoolean(false);

    private String port = DEFAULT_PORT;
    private int pollLeft = 0;
    private boolean showingGuide = true;

    private String baseUrl() { return "http://127.0.0.1:" + port + "/"; }
    private String healthUrl() { return baseUrl() + "api/health"; }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        port = prefs.getString(KEY_PORT, DEFAULT_PORT);
        if (port == null || port.isEmpty()) port = DEFAULT_PORT;

        buildUi();
        setupWebView();

        // 先显示引导页，避免白屏；后台探测服务是否已在本机运行。
        showGuide("", "正在检测本地服务…", "第一次打开需要启动一次后台服务，稍等几秒会自动进入。");
        probeOnce(ok -> {
            if (ok) {
                loadApp();
            } else if (!termuxPermissionGranted()) {
                // Termux 的 RUN_COMMAND 权限必须由用户在系统设置里手动授予一次。
                setGuideState("err", "需要先给一次权限",
                        "请点「去开启权限」，在「其他权限」里打开「在 Termux 环境中运行命令」，然后返回本页。");
            } else {
                // 点开图标即自动尝试拉起 Termux 服务，用户无需输入任何命令。
                boolean launched = launchTermux();
                if (launched) {
                    setBusy(true);
                    setGuideState("", "正在自动启动本地服务…",
                            "首次使用需先在 Termux 里运行一次安装脚本；若长时间无反应，请展开下方说明。");
                    startPolling(30);
                } else {
                    setGuideState("err", "无法自动启动 Termux",
                            "请点「复制启动命令」，打开 Termux 粘贴回车，再回来点「重新检测」。");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户从 Termux 手动启动服务后切回来，自动进入，不必再点按钮。
        if (showingGuide && !probing.get()) {
            probeOnce(ok -> { if (ok) loadApp(); });
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        pool.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#10131A"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#10131A"));
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
        s.setUserAgentString(s.getUserAgentString() + " MausBotLocal/0.9");

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
                    v.evaluateJavascript("try{setPort(" + jsq(port) + ")}catch(e){}", null);
                } else if (url.startsWith("http://127.0.0.1")) {
                    v.evaluateJavascript(BLOB_BRIDGE_JS, null);
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                super.onReceivedError(v, req, err);
                if (req.isForMainFrame() && !showingGuide) {
                    ui.post(() -> showGuide("err", "连接中断",
                            "本地服务可能已停止，点「一键启动」重新拉起。"));
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

    // ------------------------------------------------------------------ 服务探测 / 拉起

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
                Log.w(TAG, "startForegroundService 也失败", e2);
                return false;
            }
        }
    }

    private Intent termuxIntent() {
        // 常量值已对照 Termux 官方 TermuxConstants.java 校准：
        //   ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        //   EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        //   EXTRA_WORKDIR      = "com.termux.RUN_COMMAND_WORKDIR"
        //   EXTRA_BACKGROUND   = "com.termux.RUN_COMMAND_BACKGROUND"
        //   RunCommandService  = "com.termux.app.RunCommandService"
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_SCRIPT);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        return i;
    }

    /** 权限是否已授予。Termux 的 RUN_COMMAND 是 dangerous 权限，需用户手动开一次。 */
    private boolean termuxPermissionGranted() {
        return checkSelfPermission("com.termux.permission.RUN_COMMAND")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 打开本 App 的系统权限页，引导用户开启「在 Termux 中运行命令」。 */
    private void openAppPermissionSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            if (pollLeft-- <= 0) {
                probing.set(false);
                setBusy(false);
                setGuideState("err", "服务还没起来",
                        "请点「复制启动命令」，打开 Termux 粘贴回车，再回来点「重新检测」。");
                return;
            }
            probeOnce(ok -> {
                if (ok) {
                    stopPolling();
                    loadApp();
                } else {
                    ui.postDelayed(this, 1200);
                }
            });
        }
    };

    private void startPolling(int times) {
        stopPolling();
        pollLeft = times;
        probing.set(true);
        ui.post(pollTick);
    }

    private void stopPolling() {
        ui.removeCallbacks(pollTick);
        pollLeft = 0;
        probing.set(false);
    }

    // ------------------------------------------------------------------ 页面切换

    private void loadApp() {
        stopPolling();
        setBusy(false);
        showingGuide = false;
        web.loadUrl(baseUrl());
    }

    private void showGuide(String state, String text, String hint) {
        showingGuide = true;
        web.loadUrl(GUIDE_URL);
        final String st = state, tx = text, hn = hint;
        ui.postDelayed(() -> setGuideState(st, tx, hn), 260);
    }

    private void setGuideState(String state, String text, String hint) {
        if (web == null || !showingGuide) return;
        String js = "try{setState({state:" + jsq(state) + ",text:" + jsq(text)
                + ",hint:" + jsq(hint) + "})}catch(e){}";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void setBusy(boolean busy) {
        if (web == null || !showingGuide) return;
        ui.post(() -> web.evaluateJavascript("try{setBusy(" + busy + ")}catch(e){}", null));
    }

    private static String jsq(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "") + "'";
    }

    // ------------------------------------------------------------------ 下载 / 权限

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
            try { requestPermissions(new String[]{p}, REQ_PERM); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ JS 桥

    private class JsBridge {

        @JavascriptInterface
        public void openPermissionSettings() {
            ui.post(() -> openAppPermissionSettings());
        }

        @JavascriptInterface
        public void startService() {
            ui.post(() -> {
                if (!termuxPermissionGranted()) {
                    setGuideState("err", "需要先给一次权限",
                            "请点「去开启权限」，在「其他权限」里打开「在 Termux 环境中运行命令」，然后返回本页。");
                    return;
                }
                boolean launched = launchTermux();
                if (!launched) {
                    setGuideState("err", "无法自动启动 Termux",
                            "请点「复制启动命令」，打开 Termux 粘贴回车，再回来点「重新检测」。");
                    return;
                }
                setBusy(true);
                setGuideState("", "正在启动本地服务…", "请保持在这个页面，几秒后会自动进入。");
                startPolling(30);
            });
        }

        @JavascriptInterface
        public void recheck() {
            ui.post(() -> {
                setGuideState("", "正在检测…", "");
                probeOnce(ok -> {
                    if (ok) loadApp();
                    else setGuideState("err", "仍未检测到服务",
                            "请确认 Termux 里的启动命令已经运行，或点「一键启动」重试。");
                });
            });
        }

        @JavascriptInterface
        public void copyCommand() {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("启动命令", MANUAL_CMD));
                Toast.makeText(MainActivity.this, "已复制，打开 Termux 粘贴回车即可", Toast.LENGTH_LONG).show();
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
                Toast.makeText(MainActivity.this, "端口已设为 " + np + "，正在重连", Toast.LENGTH_SHORT).show();
                probeOnce(ok -> {
                    if (ok) loadApp();
                    else setGuideState("err", "端口 " + np + " 上没有服务",
                            "请确认 Termux 里启动时用了相同端口，或改回 8799。");
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
