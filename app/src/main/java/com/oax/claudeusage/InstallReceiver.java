package com.oax.claudeusage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** 자기 업데이트 설치 결과. 확인이 필요하면 시스템 설치 확인창을 띄운다. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        Updater.finished(ctx);
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String msg;
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            msg = "업데이트를 취소했어요";
        } else {
            String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            msg = "업데이트 실패" + (detail != null ? ": " + detail : "");
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }
}
