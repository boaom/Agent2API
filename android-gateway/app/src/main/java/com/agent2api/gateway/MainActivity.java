package com.agent2api.gateway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网关 App 的唯一 Activity：启动前台服务，等 127.0.0.1:3065/health 可用后
 * 把 WebView 指向手机本机的手机面板 /m。
 */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION = 1001;

    private WebView webView;
    private LinearLayout overlay;
    private TextView statusView;
    private TextView addressView;
    private Button retryButton;
    private Button logButton;
    private ProgressBar progress;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private boolean loaded = false;
    private boolean destroyed = false;
    private long startedAt = 0;
    private String panelMode = "mobile";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panelMode = panelMode(getIntent());
        requestNotificationPermission();
        setContentView(buildContent());
        GatewayService.start(this);
        startedAt = SystemClock.elapsedRealtime();
        handler.postDelayed(this::pollOnce, 300);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String requested = panelMode(intent);
        if (!requested.equals(panelMode)) {
            panelMode = requested;
            if (loaded && webView != null) loadPanel();
        }
        if (!loaded) {
            GatewayService.start(this);
            pollOnce();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loaded && !polling.get()) {
            GatewayService.start(this);
            handler.postDelayed(this::pollOnce, 300);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F3F6);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), 0, dp(8), 0);
        toolbar.setBackgroundColor(0xFFFFFFFF);
        toolbar.setElevation(dp(1));

        TextView appTitle = new TextView(this);
        appTitle.setText("Agent2API 网关");
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setTextColor(0xFF17171A);
        toolbar.addView(appTitle, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        menuButton.setTextColor(0xFF33333A);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setPadding(dp(14), 0, dp(14), 0);
        menuButton.setContentDescription("菜单");
        menuButton.setOnClickListener(this::showMenu);
        toolbar.addView(menuButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        FrameLayout content = new FrameLayout(this);
        webView = new WebView(this);
        configureWebView(webView);
        webView.setVisibility(View.INVISIBLE);
        content.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        overlay.setBackgroundColor(0xFFF3F3F6);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(18));
        card.setBackground(cardBackground);

        TextView title = new TextView(this);
        title.setText("Agent2API 网关");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(0xFF17171A);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        progressParams.topMargin = dp(18);
        card.addView(progress, progressParams);

        statusView = new TextView(this);
        statusView.setText("正在启动网关…");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(0xFF555560);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(14);
        card.addView(statusView, statusParams);

        addressView = new TextView(this);
        addressView.setText("本机 http://127.0.0.1:3065/m · 局域网 http://<手机IP>:3065/m");
        addressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addressView.setTextColor(0xFF777782);
        addressView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addressParams.topMargin = dp(8);
        card.addView(addressView, addressParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(18);

        retryButton = new Button(this);
        retryButton.setText("重试");
        retryButton.setAllCaps(false);
        retryButton.setOnClickListener(view -> retry());
        retryButton.setVisibility(View.GONE);
        buttons.addView(retryButton);

        logButton = new Button(this);
        logButton.setText("查看日志");
        logButton.setAllCaps(false);
        logButton.setOnClickListener(view -> showLog());
        logButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logParams.leftMargin = dp(8);
        buttons.addView(logButton, logParams);

        card.addView(buttons, buttonsParams);
        overlay.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " Agent2APIGateway/3.4.2");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(view, true);
        view.setBackgroundColor(0xFFF3F3F6);
        view.setWebChromeClient(new WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView webView, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    loaded = false;
                    webView.setVisibility(View.INVISIBLE);
                    overlay.setVisibility(View.VISIBLE);
                    statusView.setText("页面加载失败，网关可能已经退出");
                    retryButton.setVisibility(View.VISIBLE);
                    logButton.setVisibility(View.VISIBLE);
                    handler.postDelayed(MainActivity.this::pollOnce, 800);
                }
            }
        });
    }

    private void pollOnce() {
        if (destroyed || loaded) return;
        if (!polling.compareAndSet(false, true)) {
            handler.postDelayed(this::pollOnce, 250);
            return;
        }
        new Thread(() -> {
            boolean up = probeHealth();
            runOnUiThread(() -> {
                polling.set(false);
                if (destroyed) return;
                if (up) {
                    showWeb();
                    return;
                }
                updateStatus();
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                handler.postDelayed(this::pollOnce, elapsed > 15_000 ? 1500 : 600);
            });
        }, "gateway-health").start();
    }

    private void showWeb() {
        if (loaded || destroyed) return;
        loaded = true;
        overlay.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        loadPanel();
    }

    private void loadPanel() {
        boolean desktop = "desktop".equals(panelMode);
        WebSettings settings = webView.getSettings();
        settings.setUseWideViewPort(desktop);
        settings.setLoadWithOverviewMode(desktop);
        settings.setSupportZoom(desktop);
        settings.setBuiltInZoomControls(desktop);
        settings.setDisplayZoomControls(false);
        webView.loadUrl("http://127.0.0.1:" + GatewayService.PORT + (desktop ? "/" : "/m"));
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(1, 1, 0, "手机面板").setChecked("mobile".equals(panelMode));
        menu.add(1, 2, 1, "桌面面板").setChecked("desktop".equals(panelMode));
        menu.setGroupCheckable(1, true, true);
        menu.add(2, 3, 2, "在浏览器打开");
        menu.add(2, 4, 3, "重启网关");
        menu.add(2, 5, 4, "查看日志");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                case 2:
                    panelMode = item.getItemId() == 2 ? "desktop" : "mobile";
                    if (loaded) loadPanel();
                    return true;
                case 3:
                    openInBrowser();
                    return true;
                case 4:
                    retry();
                    return true;
                case 5:
                    showLog();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void openInBrowser() {
        String base = "http://127.0.0.1:" + GatewayService.PORT
            + ("desktop".equals(panelMode) ? "/" : "/m");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(base)));
        } catch (Exception error) {
            statusView.setText("没有可用的浏览器：" + error.getMessage());
        }
    }

    private static String panelMode(Intent intent) {
        if (intent == null) return "mobile";
        String value = intent.getStringExtra("panel");
        return "desktop".equals(value) ? "desktop" : "mobile";
    }

    private void updateStatus() {
        String state = GatewayService.state;
        String error = GatewayService.lastError;
        if ("running".equals(state)) {
            statusView.setText("网关进程已启动，正在等待面板响应…");
        } else if ("starting".equals(state) || "extracting".equals(state)) {
            statusView.setText("正在启动网关…");
        } else if ("restarting".equals(state)) {
            statusView.setText("网关退出，正在重启…");
        } else if ("error".equals(state)) {
            statusView.setText(TextUtils.isEmpty(error) ? "网关启动失败" : error);
        } else {
            statusView.setText(TextUtils.isEmpty(error) ? "网关未启动" : error);
        }
        String ip = GatewayService.localIpv4();
        addressView.setText("本机 http://127.0.0.1:" + GatewayService.PORT + "/m"
            + (ip == null ? "" : " · 局域网 http://" + ip + ":" + GatewayService.PORT + "/m"));
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        boolean show = elapsed > 12_000 || "error".equals(state);
        retryButton.setVisibility(show ? View.VISIBLE : View.GONE);
        logButton.setVisibility(show ? View.VISIBLE : View.GONE);
        progress.setVisibility("error".equals(state) ? View.GONE : View.VISIBLE);
    }

    private void retry() {
        loaded = false;
        retryButton.setVisibility(View.GONE);
        logButton.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        statusView.setText("正在重启网关…");
        startedAt = SystemClock.elapsedRealtime();
        GatewayService.restart(this);
        handler.postDelayed(this::pollOnce, 500);
    }

    private void showLog() {
        String log = GatewayService.readLogTail(this);
        TextView text = new TextView(this);
        text.setText(log);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextIsSelectable(true);
        int padding = dp(16);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
            .setTitle("网关日志")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show();
    }

    private boolean probeHealth() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + GatewayService.PORT + "/health").openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setUseCaches(false);
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}