package com.beneficiaries.sender;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PluginMethod;

import java.net.URLEncoder;
import java.util.ArrayList;

@CapacitorPlugin(name = "Sender", permissions = {
    @Permission(strings = {Manifest.permission.SEND_SMS}, alias = "sms")
})
public class SenderPlugin extends Plugin {

    @PluginMethod
    public void sendSms(PluginCall call) {
        if (getPermissionState("sms") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("sms", call, "smsPermCallback");
            return;
        }
        doSendSms(call);
    }

    @PermissionCallback
    private void smsPermCallback(PluginCall call) {
        if (getPermissionState("sms") == com.getcapacitor.PermissionState.GRANTED) doSendSms(call);
        else call.reject("no permission", "NO_PERM");
    }

    private void doSendSms(PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        if (phone == null || message == null) { call.reject("missing data"); return; }
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31
                ? getContext().getSystemService(SmsManager.class)
                : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(message);
            sm.sendMultipartTextMessage(phone, null, parts, null, null);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void smsManual(PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        if (phone == null || message == null) { call.reject("missing data"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
            i.putExtra("sms_body", message);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void whatsapp(PluginCall call) {
        String phone = call.getString("phone");
        String message = call.getString("message");
        boolean business = Boolean.TRUE.equals(call.getBoolean("business", false));
        boolean auto = Boolean.TRUE.equals(call.getBoolean("auto", false));
        if (phone == null || message == null) { call.reject("missing data"); return; }

        if (auto && !isServiceEnabled()) {
            call.reject("service off", "NO_SERVICE");
            return;
        }
        try {
            String url = "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, "UTF-8");
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage(business ? "com.whatsapp.w4b" : "com.whatsapp");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (auto) {
                WhatsAppAccessibilityService.pendingCall = call;
                WhatsAppAccessibilityService.armed = true;
                call.setKeepAlive(true);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (WhatsAppAccessibilityService.pendingCall == call) {
                        WhatsAppAccessibilityService.armed = false;
                        WhatsAppAccessibilityService.pendingCall = null;
                        call.reject("timeout", "TIMEOUT");
                    }
                }, 25000);
            }
            getContext().startActivity(i);
            if (!auto) call.resolve();
        } catch (ActivityNotFoundException e) {
            WhatsAppAccessibilityService.armed = false;
            WhatsAppAccessibilityService.pendingCall = null;
            call.reject("whatsapp not found", "NOT_FOUND");
        } catch (Exception e) {
            WhatsAppAccessibilityService.armed = false;
            WhatsAppAccessibilityService.pendingCall = null;
            call.reject(String.valueOf(e.getMessage()));
        }
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
