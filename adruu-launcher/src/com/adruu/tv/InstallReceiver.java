package com.adruu.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;

/** Shows Android's "Install this app?" screen for APKs sent from the phone. */
public class InstallReceiver extends BroadcastReceiver {
    static IntentSender pending(Context c, int sessionId) {
        Intent i = new Intent(c, InstallReceiver.class);
        return PendingIntent.getBroadcast(c, sessionId, i, PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender();
    }

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Prefs p = new Prefs(c);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            p.addEvent("App installed");
        } else {
            p.addEvent("App install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
