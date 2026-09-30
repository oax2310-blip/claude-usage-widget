package com.oax.claudeusage;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity {
    private Store store;
    private TextView tvDaily, tvPace, tvCountdown, tvPaceLabel, tvUsedLabel, tvUsageInfo, tvVersion;
    private ProgressBar pbPace, pbUsed;
    private Button btnReset, btnUpdate;
    private EditText etPeriod, etUsed;
    /** 설치 가능한 새 버전(없으면 null) */
    private Updater.Release latest;
    /** '출처를 알 수 없는 앱' 허용하러 설정에 다녀오는 중 */
    private boolean wantInstall;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        tvDaily = findViewById(R.id.tv_daily);
        tvPace = findViewById(R.id.tv_pace);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvPaceLabel = findViewById(R.id.tv_pace_label);
        tvUsedLabel = findViewById(R.id.tv_used_label);
        tvUsageInfo = findViewById(R.id.tv_usage_info);
        pbPace = findViewById(R.id.pb_pace);
        pbUsed = findViewById(R.id.pb_used);
        btnReset = findViewById(R.id.btn_reset);
        etPeriod = findViewById(R.id.et_period);
        etUsed = findViewById(R.id.et_used);
        btnUpdate = findViewById(R.id.btn_update);
        tvVersion = findViewById(R.id.tv_version);

        // 상태바·내비게이션바·키보드에 가리지 않도록 여백 적용(Android 15 전체화면 대응)
        View content = findViewById(R.id.content);
        final int pl = content.getPaddingLeft(), pt = content.getPaddingTop();
        final int pr = content.getPaddingRight(), pb = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(pl + b.left, pt + b.top, pr + b.right, pb + b.bottom);
            return insets;
        });

        btnReset.setOnClickListener(v -> pickReset());
        btnUpdate.setOnClickListener(v -> startUpdate());
        tvVersion.setText("버전 " + Updater.currentVersionName(this) + " · 눌러서 업데이트 확인");
        tvVersion.setOnClickListener(v -> checkUpdate(true));
        findViewById(R.id.btn_period).setOnClickListener(v -> applyPeriod());
        findViewById(R.id.btn_save_used).setOnClickListener(v -> saveUsed());
        findViewById(R.id.btn_clear_used).setOnClickListener(v -> {
            store.clearUsed();
            etUsed.setText("");
            afterChange();
        });
        etPeriod.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { applyPeriod(); return true; }
            return false;
        });
        etUsed.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { saveUsed(); return true; }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        etPeriod.setText(trim(store.periodDays()));
        handler.post(ticker);

        Updater.setListener(this::renderUpdate);
        renderUpdate();
        if (wantInstall) {
            wantInstall = false;
            if (getPackageManager().canRequestPackageInstalls()) startUpdate();
        }
        checkUpdate(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        Updater.setListener(null);
        UsageWidget.updateAll(this);
    }

    private void checkUpdate(boolean manual) {
        Updater.check(this, manual, rel -> {
            if (isDestroyed()) return;
            if (rel == null) {
                if (manual) toast("업데이트 확인 실패 — 인터넷 연결을 확인해 주세요");
                return;
            }
            latest = rel.versionCode > Updater.currentVersionCode(this) ? rel : null;
            if (manual && latest == null) toast("최신 버전이에요");
            renderUpdate();
        });
    }

    private void startUpdate() {
        if (latest == null || Updater.busy()) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            // 처음 한 번만: 이 앱이 업데이트를 설치할 수 있게 허용
            wantInstall = true;
            toast("'이 출처 허용'을 켜고 돌아오면 업데이트가 시작돼요");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Updater.install(this, latest);
    }

    private void renderUpdate() {
        if (latest == null) {
            btnUpdate.setVisibility(View.GONE);
            return;
        }
        boolean busy = Updater.busy();
        btnUpdate.setVisibility(View.VISIBLE);
        btnUpdate.setEnabled(!busy);
        btnUpdate.setText(busy
                ? "업데이트 중… 끝나면 앱이 닫혀요(다시 열어 주세요)"
                : "새 버전 " + latest.versionName + " 업데이트");
    }

    private void pickReset() {
        final Calendar c = Calendar.getInstance();
        long cur = store.resetAt();
        if (cur > 0) c.setTimeInMillis(cur);
        new DatePickerDialog(this, (dp, y, m, d) ->
                new TimePickerDialog(this, (tp, h, min) -> {
                    Calendar s = Calendar.getInstance();
                    s.set(y, m, d, h, min, 0);
                    s.set(Calendar.MILLISECOND, 0);
                    store.setResetAt(s.getTimeInMillis());
                    afterChange();
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void applyPeriod() {
        Float v = parse(etPeriod.getText().toString());
        if (v == null || v < 1 || v > 31) {
            toast("주기는 1~31일 사이로 넣어 주세요");
            etPeriod.setText(trim(store.periodDays()));
            return;
        }
        store.setPeriodDays(v);
        hideKeyboard();
        afterChange();
    }

    private void saveUsed() {
        Float v = parse(etUsed.getText().toString());
        if (v == null || v < 0 || v > 100) {
            toast("0~100 사이 숫자로 넣어 주세요");
            return;
        }
        if (store.resetAt() <= 0) toast("초기화 시각도 설정해야 페이스 비교가 돼요");
        store.setUsed(v, System.currentTimeMillis());
        hideKeyboard();
        etUsed.clearFocus();
        afterChange();
    }

    private void afterChange() {
        render();
        UsageWidget.updateAll(this);
    }

    private void render() {
        long now = System.currentTimeMillis();
        UsageCalc.Result r = store.compute(now);

        tvDaily.setText(Fmt.pct(r.dailyBase));
        if (!r.configured) {
            tvPace.setText("--");
            tvCountdown.setText("아래 1번에서 초기화 시각을 설정하세요");
            btnReset.setText("초기화 시각 선택");
            tvPaceLabel.setVisibility(View.GONE);
            pbPace.setVisibility(View.GONE);
            tvUsedLabel.setVisibility(View.GONE);
            pbUsed.setVisibility(View.GONE);
            tvUsageInfo.setVisibility(View.GONE);
            return;
        }

        tvPace.setText(Fmt.pct(r.paceUsed));
        tvCountdown.setText("초기화까지 " + Fmt.duration(r.remainingMs) + "\n" + Fmt.dateLong(r.resetAt));
        btnReset.setText(Fmt.dateLong(r.resetAt));

        tvPaceLabel.setVisibility(View.VISIBLE);
        pbPace.setVisibility(View.VISIBLE);
        tvPaceLabel.setText("초기화까지 남은 권장 " + Fmt.pct(r.paceRemaining));
        pbPace.setProgress((int) Math.round(r.paceUsed * 10));

        tvUsageInfo.setVisibility(View.VISIBLE);
        if (r.hasUsage) {
            tvUsedLabel.setVisibility(View.VISIBLE);
            pbUsed.setVisibility(View.VISIBLE);
            tvUsedLabel.setText("실제 사용 " + Fmt.pct(r.used) + " (" + Fmt.age(r.usageAgeMs) + " 입력)");
            pbUsed.setProgress((int) Math.round(r.used * 10));

            boolean over = r.paceDiffAtInput < 0;
            String pace = over
                    ? "권장보다 " + Fmt.pp(r.paceDiffAtInput) + " 더 썼어요"
                    : "권장보다 " + Fmt.pp(r.paceDiffAtInput) + " 덜 썼어요(여유)";
            String plan = r.lessThanDay
                    ? "초기화 전까지 남은 한도 " + Fmt.pct(r.leftLimit) + " 사용 가능"
                    : "남은 한도 " + Fmt.pct(r.leftLimit) + " → 남은 기간 하루 " + Fmt.pct(r.dailyAdjusted);
            tvUsageInfo.setText(pace + " (입력 시점 기준)\n" + plan);
            tvUsageInfo.setTextColor(getColor(over ? R.color.warn : R.color.text_primary));
        } else {
            tvUsedLabel.setVisibility(View.GONE);
            pbUsed.setVisibility(View.GONE);
            tvUsageInfo.setText("현재 사용량을 넣으면 페이스 비교가 나와요 (선택)");
            tvUsageInfo.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private static Float parse(String s) {
        s = s.trim().replace(',', '.').replace("%", "");
        if (s.isEmpty()) return null;
        try {
            float v = Float.parseFloat(s);
            return Float.isNaN(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(float v) {
        if (v == Math.rint(v)) return String.valueOf((int) v);
        return String.format(Locale.KOREA, "%.1f", v);
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (f != null && imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
