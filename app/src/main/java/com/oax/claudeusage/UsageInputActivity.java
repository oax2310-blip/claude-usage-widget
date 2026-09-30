package com.oax.claudeusage;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/** 한 줄 위젯의 사용량 숫자를 누르면 뜨는 입력 창: 앱을 열지 않고 현재 사용량(%)만 바로 수정. */
public class UsageInputActivity extends Activity {
    private Store store;
    private EditText et;
    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        UsageCalc.Result r = store.compute(System.currentTimeMillis());

        et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setSingleLine(true);
        et.setHint("0~100");
        if (r.hasUsage) {
            et.setText(MainActivity.trim((float) r.used));
            et.selectAll();
        }
        et.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("현재 사용량(%)")
                .setView(box)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null);
        if (r.hasUsage) {
            b.setNeutralButton("지우기", (d, w) -> {
                store.clearUsed();
                done();
            });
        }
        dialog = b.create();
        // 저장은 값이 올바를 때만 닫히도록 직접 처리
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> save()));
        dialog.setOnDismissListener(d -> finish());
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        et.requestFocus();
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
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
        dialog.dismiss();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
