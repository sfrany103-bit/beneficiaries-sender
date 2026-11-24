package com.beneficiaries.sender;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;

import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.net.URLEncoder;
import java.util.ArrayList;

@CapacitorPlugin(name = "Sender", permissions = {
    @Permission(strings = {Manifest.permission.SEND_SMS}, alias = "sms"),
    @Permission(strings = {Manifest.permission.READ_SMS}, alias = "smsRead"),
    @Permission(strings = {Manifest.permission.READ_PHONE_STATE}, alias = "phone")
})
public class SenderPlugin extends Plugin {

    /* ---------- SMS تلقائي ---------- */

    @PluginMethod
    public void sendSms(PluginCall call) {
        if (!hasSendPerms()) {
            requestPermissionForAliases(new String[]{"sms", "phone"}, call, "smsPermCallback");
            return;
        }
        doSendSms(call);
    }

    @PermissionCallback
    private void smsPermCallback(PluginCall call) {
        if (hasSendPerms()) doSendSms(call);
        else call.reject("no permission", "NO_PERM");
    }

    private boolean hasSendPerms() {
        return getPermissionState("sms") == PermissionState.GRANTED
            && getPermissionState("phone") == PermissionState.GRANTED;
    }

    private void doSendSms(final PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        if (phone == null || message == null) { call.reject("missing data"); return; }
        final Context ctx = getContext();
        try {
            SmsManager sm;
            try {
                sm = SmsManager.getDefault();
            } catch (Exception e0) {
                sm = ctx.getSystemService(SmsManager.class);
            }
            ArrayList<String> parts = sm.divideMessage(message);
            final int total = parts.size();
            final String action = "com.beneficiaries.sender.SMS_SENT_" + System.nanoTime();
            final int[] done = {0};
            final boolean[] failed = {false};
            final boolean[] finished = {false};
                        final int[] errCode = {0};
            final BroadcastReceiver[] holder = new BroadcastReceiver[1];

            holder[0] = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent in) {
                    if (finished[0]) return;
                                        if (getResultCode() != Activity.RESULT_OK) { failed[0] = true; errCode[0] = getResultCode(); }
                    done[0]++;
                    if (done[0] >= total) {
                        finished[0] = true;
                        try { ctx.unregisterReceiver(holder[0]); } catch (Exception ignored) {}
                                                if (failed[0]) call.reject(String.valueOf(errCode[0]), "SMS_FAILED");
                        else call.resolve();
                    }
                }
            };
            IntentFilter f = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(holder[0], f, Context.RECEIVER_NOT_EXPORTED);
            else ctx.registerReceiver(holder[0], f);

            ArrayList<PendingIntent> sents = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                Intent si = new Intent(action).setPackage(ctx.getPackageName());
                sents.add(PendingIntent.getBroadcast(ctx, i, si,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            call.setKeepAlive(true);
            sm.sendMultipartTextMessage(phone, null, parts, sents, null);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!finished[0]) {
                    finished[0] = true;
                    try { ctx.unregisterReceiver(holder[0]); } catch (Exception ignored) {}
                    call.reject("timeout", "TIMEOUT");
                }
            }, 30000);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /* ---------- SMS يدوي ---------- */

    @PluginMethod
    public void smsManual(PluginCall call) {
        if (getPermissionState("smsRead") != PermissionState.GRANTED) {
            requestPermissionForAlias("smsRead", call, "smsReadCallback");
            return;
        }
        doSmsManual(call);
    }

    @PermissionCallback
    private void smsReadCallback(PluginCall call) {
        if (getPermissionState("smsRead") == PermissionState.GRANTED) doSmsManual(call);
        else call.reject("no permission", "NO_PERM");
    }

    private void doSmsManual(final PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        if (phone == null || message == null) { call.reject("missing data"); return; }
        final long start = System.currentTimeMillis();
        String digits = phone.replaceAll("\\D", "");
        final String tail = digits.length() > 8 ? digits.substring(digits.length() - 8) : digits;
        try {
            call.setKeepAlive(true);
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
            i.putExtra("sms_body", message);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (sentSince(start, tail)) {
                bringToFront();
                call.resolve();
                return;
            }
            if (System.currentTimeMillis() - start > 60000) {
                bringToFront();
                call.reject("timeout", "TIMEOUT");
                return;
            }
            h.postDelayed(poll[0], 1000);
        };
        h.postDelayed(poll[0], 1500);
    }

    private boolean sentSince(long start, String tail) {
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(Uri.parse("content://sms"),
                new String[]{"address", "type", "date"},
                "date > ?", new String[]{String.valueOf(start - 2000)}, "date DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    int type = c.getInt(1);
                    if (type != 2 && type != 4 && type != 6) continue;
                    String addr = c.getString(0);
                    if (addr != null && addr.replaceAll("\\D", "").endsWith(tail)) return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    private void bringToFront() {
        try {
            Intent back = getContext().getPackageManager()
                .getLaunchIntentForPackage(getContext().getPackageName());
            if (back != null) {
                back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                getContext().startActivity(back);
            }
        } catch (Exception ignored) {}
    }

    /* ---------- واتساب ---------- */

    @PluginMethod
    public void whatsapp(PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        boolean business = Boolean.TRUE.equals(call.getBoolean("business", false));
        boolean auto = Boolean.TRUE.equals(call.getBoolean("auto", false));
        boolean retry = Boolean.TRUE.equals(call.getBoolean("retry", false));
        if (phone == null || message == null) { call.reject("missing data"); return; }

        if (!isServiceEnabled()) {
            call.reject("service off", "NO_SERVICE");
            return;
        }
        try {
            String url = retry
                ? "https://wa.me/" + phone
                : "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, "UTF-8");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage(business ? "com.whatsapp.w4b" : "com.whatsapp");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            WhatsAppAccessibilityService.pendingCall = call;
            WhatsAppAccessibilityService.manualMode = !auto;
            WhatsAppAccessibilityService.reuseDraft = retry;
            WhatsAppAccessibilityService.pendingText = message;
            WhatsAppAccessibilityService.entrySeen = 0;
            WhatsAppAccessibilityService.armed = true;
            call.setKeepAlive(true);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (WhatsAppAccessibilityService.pendingCall == call) {
                    resetService();
                    bringToFront();
                    call.reject("timeout", "TIMEOUT");
                }
            }, auto ? 12000 : 60000);

            getContext().startActivity(i);
        } catch (ActivityNotFoundException e) {
            resetService();
            call.reject("whatsapp not found", "NOT_FOUND");
        } catch (Exception e) {
            resetService();
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    private void resetService() {
        WhatsAppAccessibilityService.armed = false;
        WhatsAppAccessibilityService.manualMode = false;
        WhatsAppAccessibilityService.reuseDraft = false;
        WhatsAppAccessibilityService.pendingText = null;
        WhatsAppAccessibilityService.entrySeen = 0;
        WhatsAppAccessibilityService.pendingCall = null;
    }

    @PluginMethod
    public void openAccessibility(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void openOverlay(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    private boolean isServiceEnabled() {
        try {
            String s = Settings.Secure.getString(getContext().getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getContext().getPackageName())
                && s.contains("WhatsAppAccessibilityService");
        } catch (Exception e) {
            return false;
        }
    }
}
