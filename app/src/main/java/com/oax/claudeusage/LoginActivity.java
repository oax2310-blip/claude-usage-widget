package com.oax.claudeusage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 앱 안에서 claude.ai에 로그인하는 화면. 로그인을 마치면 사용량을 한 번 읽어 보고, 되면 자동 읽기를 켜고 닫힌다.
 * 로그인(쿠키)은 이 앱의 WebView 안에만 저장되고 백업에서도 빠진다.
 */
public class LoginActivity extends Activity {
    private static final String HINT = "claude.ai에 로그인하면 사용량을 읽고 자동으로 닫혀요.\n"
            + "앱 안에서는 Google 로그인이 막힐 수 있어요 — 같은 이메일 주소를 넣어 로그인 코드를 받거나, "
            + "메일의 로그인 링크를 길게 눌러 복사한 뒤 '링크 붙여넣기'를 누르세요.";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            check(false);
            handler.postDelayed(this, 1500);
        }
    };
    private WebView web;
    private TextView status;
    /** 이미 읽기를 해 본 화면 주소(같은 화면에서 되풀이하지 않음) */
    private String triedUrl;
    private boolean syncing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Math.round(12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.app_bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad / 2, pad / 2, 0);
        TextView title = new TextView(this);
        title.setText("Claude 로그인");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button paste = new Button(this);
        paste.setText("링크 붙여넣기");
        paste.setOnClickListener(v -> pasteLink());
        bar.addView(paste);
        Button close = new Button(this);
        close.setText("닫기");
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        root.addView(bar);

        status = new TextView(this);
        status.setPadding(pad, pad / 3, pad, pad / 2);
        status.setTextColor(getColor(R.color.text_secondary));
        status.setTextSize(13);
        status.setText(HINT);
        status.setOnClickListener(v -> check(true));
        root.addView(status);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                // mailto: 등은 다른 앱으로
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                check(false);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // 상태바·내비게이션바·키보드에 가리지 않도록(Android 15 전체화면 대응)
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(UsageApi.ORIGIN + "/login");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** 로그인을 마친 화면이면 사용량을 읽어 봄(화면이 바뀔 때마다 한 번, 안내 문구를 누르면 다시) */
    private void check(boolean force) {
        if (syncing || isFinishing()) return;
        String url = web.getUrl();
        if (!UsageApi.isSignedInPage(url)) {
            if (force) status.setText(HINT);
            return;
        }
        if (!force && url.equals(triedUrl)) return;
        triedUrl = url;
        syncing = true;
        status.setText("로그인 확인 — 사용량 읽는 중…");
        UsageSync.run(this, (ok, err) -> {
            syncing = false;
            if (isDestroyed()) return;
            if (ok) {
                Toast.makeText(this, "연결됐어요 — 이제 사용량을 자동으로 읽어요", Toast.LENGTH_SHORT).show();
                UsageWidget.updateAll(this);
                finish();
            } else {
                status.setText("아직 못 읽었어요: " + err + "\n(로그인을 마쳤다면 여기를 눌러 다시 시도)");
            }
        });
    }

    /** 메일의 로그인 링크를 복사해 왔으면 이 화면에서 열기(링크가 폰 브라우저에서 열리면 앱에는 로그인되지 않음) */
    private void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        String u = "";
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            if (t != null) u = t.toString().trim();
        }
        if (u.startsWith("https://")) {
            web.loadUrl(u);
        } else {
            Toast.makeText(this, "메일의 로그인 링크를 길게 눌러 복사한 뒤 눌러 주세요", Toast.LENGTH_LONG).show();
        }
    }
}
