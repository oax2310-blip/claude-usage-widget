package com.oax.claudeusage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 앱 안에서 로그인한 claude.ai 세션으로 주간 사용량·초기화 시각을 읽어 저장한다.
 * claude.ai는 브라우저가 아닌 요청을 Cloudflare 확인 화면으로 막아서, 로그인 화면과 같은 WebView(크롬 엔진)를
 * 화면 없이 띄워 JSON 주소를 열고 본문을 읽는다. 메인 스레드에서만 호출.
 */
final class UsageSync {
    interface Callback {
        void done(boolean ok, String error);
    }

    /** 앱을 열 때 이 간격 안에 읽었으면 다시 읽지 않음 */
    static final long RESUME_INTERVAL = UsageCalc.MINUTE;
    /** 위젯 자동 갱신(30분마다·오전 3시) 때 이 간격이 지났을 때만 읽음 */
    static final long AUTO_INTERVAL = 25 * UsageCalc.MINUTE;
    private static final long TIMEOUT = 25_000;
    /** JSON 응답은 <pre> 안에 글자로 보임 */
    private static final String READ_BODY =
            "(function(){var e=document.querySelector('pre')||document.body;return e?e.innerText:'';})()";

    private static Job job;

    private UsageSync() {}

    static boolean busy() { return job != null; }

    /** 읽기 시작(이미 읽는 중이면 그 결과를 같이 받음). 결과는 메인 스레드로 */
    static void run(Context ctx, Callback cb) {
        if (job == null) {
            job = new Job(ctx.getApplicationContext());
            job.waiting.add(cb);
            job.start();
        } else {
            job.waiting.add(cb);
        }
    }

    /** 연결 해제: 앱 안의 claude.ai 로그인(쿠키·저장소)을 지움 */
    static void unlink(Context ctx) {
        new Store(ctx).setLinked(false);
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();
    }

    private static final class Job {
        final Context app;
        final Store store;
        final Handler handler = new Handler(Looper.getMainLooper());
        final List<Callback> waiting = new ArrayList<>();
        final Runnable timeout = this::onTimeout;
        WebView web;
        /** 고른 조직(없으면 아직 조직 목록 단계) */
        String org;
        /** JSON이 아닌 화면을 만났는지(Cloudflare 확인 화면 등) */
        boolean sawOther;
        boolean ended;

        Job(Context app) {
            this.app = app;
            this.store = new Store(app);
        }

        void start() {
            store.setSyncTry(System.currentTimeMillis());
            try {
                web = new WebView(app);
            } catch (RuntimeException e) {
                // WebView 업데이트 중 등
                end(false, "WebView를 열지 못했어요 — 잠시 뒤 다시 해 주세요", false);
                return;
            }
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    if (ended) return;
                    if (UsageApi.isLoginPage(url)) {
                        end(false, "로그인이 풀렸어요 — 다시 로그인해 주세요", true);
                        return;
                    }
                    v.evaluateJavascript(READ_BODY, value -> onBody(url, value));
                }

                @Override
                public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                    if (req.isForMainFrame()) end(false, "인터넷 연결을 확인해 주세요", false);
                }
            });
            handler.postDelayed(timeout, TIMEOUT);
            web.loadUrl(UsageApi.orgsUrl());
        }

        void onBody(String url, String value) {
            if (ended) return;
            String expected = org == null ? UsageApi.orgsUrl() : UsageApi.usageUrl(org);
            // 이전 단계 페이지의 늦은 알림은 무시
            if (!UsageApi.samePage(url, expected)) return;
            Object v = UsageApi.json(UsageApi.decodeJs(value));
            if (v == null) {
                // JSON이 아님(Cloudflare 확인 화면 등): 확인이 끝나 다시 열리기를 기다림
                sawOther = true;
                return;
            }
            try {
                if (org == null) {
                    String last = UsageApi.cookie(CookieManager.getInstance().getCookie(UsageApi.ORIGIN),
                            "lastActiveOrg");
                    org = UsageApi.pickOrg(v, last);
                    web.loadUrl(UsageApi.usageUrl(org));
                } else {
                    UsageApi.Usage u = UsageApi.parseUsage(v);
                    store.applySync(u.used, u.resetAt, System.currentTimeMillis());
                    end(true, null, false);
                }
            } catch (UsageApi.ApiException e) {
                end(false, e.getMessage(), e.needLogin);
            }
        }

        void onTimeout() {
            // 보안 확인 화면은 화면이 있는 로그인 창에서 통과시키면 됨 → '다시 로그인'으로 안내
            if (sawOther) end(false, "claude.ai 보안 확인에 막혔어요 — '다시 로그인'을 눌러 확인해 주세요", true);
            else end(false, "시간이 너무 오래 걸려요 — 인터넷 연결을 확인해 주세요", false);
        }

        void end(boolean ok, String error, boolean needLogin) {
            if (ended) return;
            ended = true;
            handler.removeCallbacks(timeout);
            if (!ok) store.setSyncError(error, needLogin);
            final WebView w = web;
            web = null;
            if (w != null) {
                // WebView 콜백 안에서 바로 없애지 않도록 한 박자 뒤에
                handler.post(() -> {
                    w.stopLoading();
                    w.destroy();
                });
            }
            CookieManager.getInstance().flush();
            job = null;
            for (Callback c : waiting) c.done(ok, error);
        }
    }
}
