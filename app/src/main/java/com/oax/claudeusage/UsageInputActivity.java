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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.time.ZoneId;

/**
 * 사용량 입력 팝업(한 줄 위젯의 숫자를 누르거나 앱에서 열기): 위쪽에 claude.ai 설정 → 사용량 페이지를 띄워
 * 직접 보면서 아래에 현재 사용량(%)을 넣는다. 팝업이 떠 있는 동안에만, 화면에 그려진 페이지 글자에서
 * 주간 사용량을 찾아 입력 칸에 채우고(폰 안에서만 읽고 서버에 따로 요청하지 않음), 앱에서 정한 시간(기본 1.2초)
 * 뒤 자동으로 저장하고 닫는다(그 사이 숫자 키·입력 칸·페이지를 만지면 자동 저장 취소).
 * 같은 때 5시간(현재 세션) 사용량과 그 초기화 시각도 읽어 두었다가 저장할 때 함께 저장한다(위젯 아랫줄).
 * 위젯의 새로고침(↻)으로 열면 기다리지 않고 찾는 즉시 저장한다.
 * 앱이 알아서 claude.ai에 접속하지는 않는다(자동 접근은 Claude 약관 위반).
 * claude.ai 로그인은 이 앱의 WebView에만 저장되고 백업에서 빠지며, '로그아웃'으로 지울 수 있다.
 */
public class UsageInputActivity extends Activity {
    private static final String ORIGIN = "https://claude.ai";
    private static final String USAGE_URL = ORIGIN + "/settings/usage";
    /**
     * 떠 있는 설정 창들(없으면 페이지 전체)의 글자와 어디서 읽었는지 — 읽기만 하고 페이지는 건드리지 않음.
     * 설정 창이 여러 개일 수 있어서(숨은 창 등) 모두 합쳐 읽음
     */
    private static final String READ_TEXT = "(function(){"
            + "var ds=[].slice.call(document.querySelectorAll('[role=dialog]'));"
            + "var t=ds.map(function(e){return e.innerText||'';}).join('\\n');"
            + "var src='설정 창 '+ds.length+'개';"
            + "if(!t.trim()){t=document.body?document.body.innerText:'';src='페이지 전체';}"
            + "return JSON.stringify({src:src,text:t});})()";
    /** 사용량 막대가 그려질 때까지 이 간격으로 이 횟수만큼(약 18초) 다시 봄 — 짧을수록 그려지자마자 찾음 */
    private static final long READ_EVERY = 300;
    private static final int READ_MAX = 60;
    /** 위젯의 새로고침(↻)으로 열었는지: 그러면 찾은 값을 기다리지 않고 바로 저장 */
    static final String EXTRA_QUICK = "com.oax.claudeusage.QUICK";

    private Store store;
    private EditText et;
    private WebView web;
    private View loginRow, progress;
    private TextView label;
    /** 로그인 화면을 거쳤는지(로그인을 마치고 다른 화면으로 가면 사용량 페이지로 다시 보냄) */
    private boolean sawLogin;
    /** 페이지에서 가져와 채웠는지 / 사용자가 직접 눌러 고쳤는지(그러면 더 채우지 않음) */
    private boolean filled, userEdited;
    /** 읽는 중인지(화면 주소가 바뀌어도 기다린 시간을 처음부터 다시 세지 않게 — 그래서 실패 표시가 안 떴음) */
    private boolean reading;
    private int readTries;
    /** 마지막으로 읽은 주소·읽은 곳·글자 — 못 가져왔을 때 '이유 보기'에 보여 줌(폰 밖으로 안 나감) */
    private String seenUrl, seenSrc, seenText;
    /** 주간 사용량을 찾을 때 함께 읽은 5시간 사용량(못 찾았으면 null) — 저장할 때 함께 저장 */
    private PageUsage.SessionRead session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable reader = this::readPage;
    private Button saveBtn;
    /** 찾은 값을 보여 주고 이만큼(ms) 뒤 자동 저장 — 잘못 집었으면 그 사이 고칠 수 있게(0이면 바로) */
    private long autoSaveMs;
    /** 자동 저장할 시각(uptime, 0이면 자동 저장 대기 중 아님) */
    private long autoAt;
    private final Runnable autoTick = this::autoTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usage_input);
        store = new Store(this);
        boolean quick = getIntent().getBooleanExtra(EXTRA_QUICK, false);
        autoSaveMs = quick ? 0 : Math.round(store.autoSaveSec() * 1000.0);
        // 새로고침(↻)이면 예전처럼 위젯부터 바로 다시 그림(팝업을 그냥 닫아도 시각·권장 누적은 갱신)
        if (quick) UsageWidgetWide.updateAll(this);
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
        // 열리자마자 입력 칸에 커서 + 기존 값 전체 선택. 폰 키보드는 띄우지 않고 아래 한 줄 숫자 키로 입력
        // (폰 키보드가 화면 절반을 가려 위쪽 사용량 페이지가 한 번에 안 보였음)
        et = findViewById(R.id.in_used);
        et.setShowSoftInputOnFocus(false);
        if (r.hasUsage) et.setText(MainActivity.trim((float) r.used));
        et.requestFocus();
        et.selectAll();
        buildPad(findViewById(R.id.in_pad));
        et.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        saveBtn = findViewById(R.id.in_save);
        saveBtn.setOnClickListener(v -> save());
        // 입력 칸을 눌러 고치려 하면 자동 저장 취소
        et.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) cancelAutoSave();
            return false;
        });
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
        label = findViewById(R.id.in_label);
        loginRow = findViewById(R.id.in_login_row);
        progress = findViewById(R.id.in_progress);

        web = findViewById(R.id.in_web);
        // 페이지를 만져 살펴보려 하면 자동 저장 취소(팝업이 갑자기 닫히지 않게)
        web.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) cancelAutoSave();
            return false;
        });
        setupWeb();
        web.loadUrl(USAGE_URL);
    }

    /** 한 줄 숫자 키: 1~9, 0, ⌫ */
    private void buildPad(LinearLayout pad) {
        int gap = Math.round(2 * getResources().getDisplayMetrics().density);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫"};
        for (String k : keys) {
            View b;
            if ("⌫".equals(k)) {
                ImageView img = new ImageView(this);
                img.setImageResource(R.drawable.ic_backspace);
                img.setScaleType(ImageView.ScaleType.CENTER);
                img.setContentDescription("한 글자 지우기");
                b = img;
            } else {
                TextView t = new TextView(this);
                t.setText(k);
                t.setGravity(Gravity.CENTER);
                t.setTextSize(18);
                t.setTextColor(getColor(R.color.text_primary));
                b = t;
            }
            b.setBackgroundResource(R.drawable.key_bg);
            b.setOnClickListener(v -> press(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(gap, 0, gap, 0);
            pad.addView(b, lp);
        }
    }

    /** 키보드처럼: 선택된 부분(처음엔 기존 값 전체)을 바꾸거나 커서 자리에 넣기 */
    private void press(String k) {
        userEdited = true;
        cancelAutoSave();
        if (!et.hasFocus()) et.requestFocus();
        Editable t = et.getText();
        int a = Math.max(0, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
        int b = Math.max(0, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
        int pos = a;
        if (!"⌫".equals(k)) {
            int rest = t.length() - (b - a);
            t.replace(a, b, k);
            pos = a + (t.length() - rest); // 최대 글자 수에 막히면 안 들어감
        } else if (a != b) {
            t.delete(a, b);
        } else if (a > 0) {
            t.delete(a - 1, a);
            pos = a - 1;
        }
        et.setSelection(Math.min(pos, t.length()));
    }

    private void setupWeb() {
        WebSettings s = web.getSettings();
        // claude.ai 화면을 그리는 데 필요(앱은 떠 있는 화면의 글자를 읽기만 하고 페이지를 바꾸지 않음)
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 폰의 파일·다른 앱 데이터(content://)는 페이지가 못 열게
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                Uri u = req.getUrl();
                // 팝업 안에서는 claude.ai·로그인 제공자만. 다른 사이트·mailto: 등은 폰 브라우저·다른 앱으로
                if (SafeHosts.allowed(u.getScheme(), u.getHost())) return false;
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
                if (isClaudePage(url)) startReading();
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
        if (isClaudePage(url)) startReading();
    }

    /** 로그인 화면이 아닌 claude.ai 화면(사용량 페이지 주소가 바뀌어도 읽도록 주소는 따지지 않음) */
    private static boolean isClaudePage(String url) {
        return url != null && url.startsWith(ORIGIN + "/") && !isLoginPage(url);
    }

    /** claude.ai 화면이 뜨면: 막대가 그려질 때까지 잠깐씩 글자를 보고 주간 사용량을 찾음 */
    private void startReading() {
        if (filled || userEdited || reading) return;
        reading = true;
        readTries = 0;
        setLabel("현재 사용량", R.color.text_primary, 15, null);
        handler.removeCallbacks(reader);
        handler.postDelayed(reader, READ_EVERY);
    }

    private void readPage() {
        final String url = web.getUrl();
        // 로그인 중이면 멈췄다가 로그인을 마치면 다시 시작
        if (filled || userEdited || isFinishing() || !isClaudePage(url)) {
            reading = false;
            return;
        }
        web.evaluateJavascript(READ_TEXT, value -> {
            if (filled || userEdited || isDestroyed()) {
                reading = false;
                return;
            }
            seenUrl = url;
            seenSrc = null;
            seenText = null;
            String json = decodeJs(value);
            if (json != null) {
                try {
                    JSONObject o = new JSONObject(json);
                    seenSrc = o.optString("src");
                    seenText = o.optString("text");
                } catch (JSONException ignored) {
                }
            }
            Double p = PageUsage.weeklyPercent(seenText);
            if (p != null) {
                reading = false;
                session = PageUsage.session(seenText, System.currentTimeMillis(), ZoneId.systemDefault());
                fill(p);
            } else if (++readTries < READ_MAX) {
                handler.postDelayed(reader, READ_EVERY);
            } else {
                reading = false;
                setLabel("못 가져옴 ⓘ", R.color.text_secondary, 13, v -> showWhy());
            }
        });
    }

    /** 찾은 값을 입력 칸에 채우고 선택(맞으면 저장, 아니면 바로 눌러 고치면 됨) */
    private void fill(double p) {
        filled = true;
        et.setText(MainActivity.trim((float) p));
        et.requestFocus();
        et.selectAll();
        setLabel("가져온 값", R.color.accent, 15, null);
        if (autoSaveMs <= 0) {
            autoSave();
            return;
        }
        autoAt = SystemClock.uptimeMillis() + autoSaveMs;
        autoTick();
    }

    /** 자동 저장 카운트다운(1초 단위, 남은 초 올림): 1.2초면 "저장 2" → "저장 1" → 저장하고 닫기 */
    private void autoTick() {
        if (autoAt == 0 || isFinishing()) return;
        long left = autoAt - SystemClock.uptimeMillis();
        if (left > 0) {
            saveBtn.setText("저장 " + (left + 999) / 1000);
            // 다음 숫자로 바뀌는 순간(남은 시간이 1초 단위로 떨어질 때)에 맞춰 다시 그림
            handler.postDelayed(autoTick, (left - 1) % 1000 + 1);
        } else {
            autoAt = 0;
            autoSave();
        }
    }

    private void autoSave() {
        if (save()) {
            String h5 = session == null ? "" : " · 5시간 " + Fmt.usedPct(session.percent);
            Toast.makeText(getApplicationContext(), "claude.ai에서 주간 " + et.getText() + "%" + h5
                    + "를 가져와 저장했어요", Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelAutoSave() {
        if (autoAt == 0) return;
        autoAt = 0;
        handler.removeCallbacks(autoTick);
        saveBtn.setText("저장");
    }

    private void setLabel(String text, int color, int sp, View.OnClickListener click) {
        label.setText(text);
        label.setTextColor(getColor(color));
        label.setTextSize(sp);
        label.setOnClickListener(click);
        label.setClickable(click != null);
    }

    /** 못 가져왔을 때: 무엇을 읽었는지 보여 줌(캡처해서 보내 주면 문구에 맞춰 고칠 수 있게) */
    private void showWhy() {
        String text = seenText == null ? "" : seenText;
        String anchor = PageUsage.anchor(text);
        PageUsage.SessionRead s = PageUsage.session(text, System.currentTimeMillis(), ZoneId.systemDefault());
        StringBuilder m = new StringBuilder()
                .append("주소: ").append(seenUrl == null ? "(없음)" : seenUrl).append('\n')
                .append("읽은 곳: ").append(seenSrc == null ? "(못 읽음)" : seenSrc).append('\n')
                .append("기준 글자(모든 모델·주간 등): ").append(anchor == null ? "못 찾음" : "'" + anchor + "' 찾음")
                .append('\n')
                .append("5시간(현재 세션): ").append(s == null ? "못 찾음" : Fmt.usedPct(s.percent)
                        + (s.resetAt > 0 ? ", " + Fmt.clock(s.resetAt) + " 초기화" : ", 초기화 시각 못 읽음"))
                .append('\n')
                .append("% 숫자: ").append(PageUsage.percentCount(text)).append("개\n\n")
                .append("읽은 글자:\n").append(text.length() > 1500 ? text.substring(0, 1500) + "…" : text);
        new AlertDialog.Builder(this)
                .setTitle("자동 입력이 안 된 이유")
                .setMessage(m)
                .setPositiveButton("다시 읽기", (d, w) -> startReading())
                .setNegativeButton("닫기", null)
                .show();
    }

    /** evaluateJavascript 결과(JSON 문자열 리터럴) → 글자. 문자열이 아니면 null */
    private static String decodeJs(String value) {
        if (value == null) return null;
        try {
            Object o = new JSONTokener(value).nextValue();
            return o instanceof String ? (String) o : null;
        } catch (JSONException e) {
            return null;
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
        Uri link = Uri.parse(u);
        if (SafeHosts.allowed(link.getScheme(), link.getHost())) {
            web.loadUrl(u);
        } else {
            toast("claude.ai 로그인 링크만 열 수 있어요 — 메일의 로그인 링크를 길게 눌러 복사한 뒤 눌러 주세요");
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
        handler.removeCallbacks(reader);
        handler.removeCallbacks(autoTick);
        web.destroy();
        super.onDestroy();
    }

    /** 저장하고 닫기. 값이 올바르지 않으면 false */
    private boolean save() {
        Float v = MainActivity.parse(et.getText().toString());
        if (v == null || v < 0 || v > 100) {
            toast("0~100 사이 숫자로 넣어 주세요");
            return false;
        }
        if (store.resetAt() <= 0) toast("초기화 시각도 설정해야 페이스 비교가 돼요");
        store.setUsed(v, System.currentTimeMillis());
        if (session != null) store.setSession((float) session.percent, session.resetAt, session.at);
        done();
        return true;
    }

    private void done() {
        UsageWidgetWide.updateAll(this);
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
