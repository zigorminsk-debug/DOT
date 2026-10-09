package com.example.motioncalm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Получает от системного установщика статус установки обновления. */
public class InstallResultReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_STATUS = "com.example.motioncalm.INSTALL_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_INSTALL_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Установщик просит подтверждение: открываем его системное окно.
            Intent confirm;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, R.string.update_installed, Toast.LENGTH_LONG).show();
        } else {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (message == null) {
                message = "код " + status;
            }
            Toast.makeText(context, context.getString(R.string.update_failed, message), Toast.LENGTH_LONG).show();
        }
    }
}
