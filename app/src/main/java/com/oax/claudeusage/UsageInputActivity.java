package com.oax.claudeusage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 사용량 입력 팝업(한 줄 위젯의 숫자를 누르거나 앱에서 열기): 위쪽에 claude.ai 설정 → 사용량 페이지를 띄워
 * 직접 보면서 아래에 현재 사용량(%)을 넣는다. 페이지는 보여주기만 하고 앱이 내용을 읽지 않는다
 * (자동 접근·스크래핑은 Claude 약관 위반). claude.ai 로그인은 이 앱의 WebView에만 저장되고
 * 백업에서 빠지며, '로그아웃'으로 지울 수 있다.
 */
public class UsageInputActivity extends Activity {
    private static final String ORIGIN = "https://claude.ai";
    private static final String USAGE_URL = ORIGIN + "/settings/usage";

    private Store store;
    private EditText et;
    private WebView web;
    private View loginRow, progress;
    /** 로그인 화면을 거쳤는지(로그인을 마치고 다른 화면으로 가면 사용량 페이지로 다시 보냄) */
    private boolean sawLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usage_input);
        store = new Store(this);
        UsageCalc.Result r = store.compute(System.currentTimeMillis());

        // 바깥(어두운 곳)을 누르면 닫기. 상태바·내비게이션바·키보드에 가리지 않도록 여백
        View root = findViewById(R.id.in_root);
        root.setOnClickListener(v -> finish());
        final int pad = root.getPaddingLeft();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom);
            return insets;
        });
        // 처음엔 페이지를 크게 보도록 키보드 없이, 입력 칸을 누르면 값 전체가 선택된 채 키보드가 뜸
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        et = findViewById(R.id.in_used);
        if (r.hasUsage) et.setText(MainActivity.trim((float) r.used));
        et.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        findViewById(R.id.in_save).setOnClickListener(v -> save());
        View clear = findViewById(R.id.in_clear);
        if (r.hasUsage) {
            clear.setVisibility(View.VISIBLE);
            clear.setOnClickListener(v -> {
                store.clearUsed();
                done();
            });
        }
        findViewById(R.id.in_close).setOnClickListener(v -> finish());
        findViewById(R.id.in_logout).setOnClickListener(v -> confirmLogout());
        findViewById(R.id.in_paste).setOnClickListener(v -> pasteLink());
        loginRow = findViewById(R.id.in_login_row);
        progress = findViewById(R.id.in_progress);

        web = findViewById(R.id.in_web);
        setupWeb();
        web.loadUrl(USAGE_URL);
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        // claude.ai 화면을 그리는 데 필요(앱이 페이지에 스크립트를 넣거나 내용을 읽지는 않음)
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.TRANSPARENT);
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
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                onUrl(url);
            }
        });
    }

    /** 화면(주소)이 바뀔 때: 로그인 화면이면 안내 표시, 로그인을 마치면 사용량 페이지로 */
    private void onUrl(String url) {
        boolean login = isLoginPage(url);
        loginRow.setVisibility(login ? View.VISIBLE : View.GONE);
        if (login) {
            sawLogin = true;
        } else if (sawLogin && url != null && url.startsWith(ORIGIN + "/") && !url.startsWith(USAGE_URL)) {
            sawLogin = false;
            web.loadUrl(USAGE_URL);
        }
    }

    static boolean isLoginPage(String url) {
        return url != null && (url.startsWith(ORIGIN + "/login") || url.startsWith(ORIGIN + "/magic-link"));
    }

    /** 메일의 로그인 링크를 복사해 왔으면 여기서 열기(폰 브라우저에서 열면 앱에는 로그인되지 않음) */
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
            toast("메일의 로그인 링크를 길게 눌러 복사한 뒤 눌러 주세요");
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("claude.ai 로그아웃")
                .setMessage("이 앱 안에 저장된 claude.ai 로그인을 지워요. 다음에 볼 때 다시 로그인해야 해요.")
                .setPositiveButton("로그아웃", (d, w) -> {
                    WebStorage.getInstance().deleteAllData();
                    CookieManager cm = CookieManager.getInstance();
                    cm.removeAllCookies(ok -> {
                        cm.flush();
                        if (isDestroyed()) return;
                        web.clearHistory();
                        web.loadUrl(USAGE_URL);
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void save() {
        Float v = MainActivity.parse(et.getText().toString());
        if (v == null || v < 0 || v > 100) {
            toast("0~100 사이 숫자로 넣어 주세요");
            return;
        }
        if (store.resetAt() <= 0) toast("초기화 시각도 설정해야 페이스 비교가 돼요");
        store.setUsed(v, System.currentTimeMillis());
        done();
    }

    private void done() {
        UsageWidget.updateAll(this);
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
