package com.adruu.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts ADRUU's background service (phone page, modes, Claude control) at boot and after every update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            // Broadcasts run in the background, so this must be a foreground start (onStartCommand confirms it).
            try { c.startForegroundService(new Intent(c, ControlService.class)); } catch (Exception ignored) { }
        }
    }
}
