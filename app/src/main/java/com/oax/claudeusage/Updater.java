package com.oax.claudeusage;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 릴리스에서 새 버전을 확인하고, APK를 받아 스스로 업데이트한다.
 * Android 12+의 자기 업데이트라 보통 확인창 없이 설치됨(첫 업데이트·기기에 따라 확인창이 뜰 수 있음).
 */
final class Updater {
    private static final String LATEST_URL =
            "https://api.github.com/repos/oax2310-blip/claude-usage-widget/releases/latest";
    private static final String APK_NAME = "ClaudeUsage.apk";
    /** 자동 확인은 이 간격 안에서는 지난 결과를 재사용(GitHub API 호출 제한 대비) */
    private static final long CHECK_INTERVAL = 30 * UsageCalc.MINUTE;
    /** 설치 결과가 끝내 안 오면(확인창이 못 뜬 경우 등) 이 시간 뒤 다시 누를 수 있게 */
    private static final long BUSY_TIMEOUT = 5 * UsageCalc.MINUTE;
    private static final Pattern TAG_NUM = Pattern.compile("(\\d+)$");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static Release cached;
    private static long checkedAt;
    private static volatile long busySince;
    private static Runnable listener;

    private Updater() {}

    static final class Release {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final long apkSize;

        Release(int versionCode, String versionName, String apkUrl, long apkSize) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    /** 릴리스 태그(v1.0.N)의 끝 숫자 = versionCode(빌드 번호). 없으면 -1 */
    static int versionFromTag(String tag) {
        if (tag == null) return -1;
        Matcher m = TAG_NUM.matcher(tag.trim());
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static long currentVersionCode(Context c) { return info(c).getLongVersionCode(); }

    static String currentVersionName(Context c) { return info(c).versionName; }

    static boolean busy() {
        return busySince > 0 && System.currentTimeMillis() - busySince < BUSY_TIMEOUT;
    }

    /** 화면이 보이는 동안 등록 → 업데이트 진행 상태가 바뀌면 메인 스레드에서 호출 */
    static void setListener(Runnable r) { listener = r; }

    /** 최신 릴리스 확인. 결과는 메인 스레드로 전달, 실패하면 null */
    static void check(Context ctx, boolean force, Consumer<Release> cb) {
        if (!force && cached != null && System.currentTimeMillis() - checkedAt < CHECK_INTERVAL) {
            cb.accept(cached);
            return;
        }
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            Release r;
            try {
                r = fetchLatest();
            } catch (Exception e) {
                r = null;
            }
            Release res = r;
            app.getMainExecutor().execute(() -> {
                if (res != null) {
                    cached = res;
                    checkedAt = System.currentTimeMillis();
                }
                cb.accept(res);
            });
        });
    }

    /** APK를 받아 설치 세션에 바로 쓰고 커밋. 설치 결과는 InstallReceiver로 온다 */
    static void install(Context ctx, Release rel) {
        if (busy()) return;
        Context app = ctx.getApplicationContext();
        setBusy(app, true);
        IO.execute(() -> {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(app.getPackageName());
                p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                if (rel.apkSize > 0) p.setSize(rel.apkSize);
                id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection c = open(rel.apkUrl);
                    try (InputStream in = c.getInputStream();
                         OutputStream out = s.openWrite("base.apk", 0, rel.apkSize > 0 ? rel.apkSize : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        s.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent i = new Intent(app, InstallReceiver.class);
                    PendingIntent done = PendingIntent.getBroadcast(app, 0, i,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(done.getIntentSender());
                }
            } catch (Exception e) {
                if (id >= 0) {
                    try {
                        pi.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
                setBusy(app, false);
                app.getMainExecutor().execute(() -> Toast.makeText(app,
                        "업데이트 받기 실패 — 인터넷 연결을 확인해 주세요", Toast.LENGTH_LONG).show());
            }
        });
    }

    /** InstallReceiver: 설치가 끝났거나 실패·취소됨 */
    static void finished(Context ctx) { setBusy(ctx.getApplicationContext(), false); }

    private static void setBusy(Context app, boolean b) {
        busySince = b ? System.currentTimeMillis() : 0;
        app.getMainExecutor().execute(() -> {
            if (listener != null) listener.run();
        });
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection c = open(LATEST_URL);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            JSONObject o = new JSONObject(readAll(in));
            String tag = o.getString("tag_name");
            int code = versionFromTag(tag);
            if (code < 0) throw new IOException("태그 형식 오류: " + tag);
            JSONArray assets = o.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (APK_NAME.equals(a.optString("name"))) {
                    return new Release(code, tag.replaceFirst("^v", ""),
                            a.getString("browser_download_url"), a.optLong("size", -1));
                }
            }
            throw new IOException("릴리스에 " + APK_NAME + " 없음");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "ClaudeUsage-Android");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static PackageInfo info(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
