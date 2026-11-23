package com.beneficiaries.sender;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.getcapacitor.PluginCall;

import java.util.List;

public class WhatsAppAccessibilityService extends AccessibilityService {

    public static volatile PluginCall pendingCall = null;
    public static volatile boolean armed = false;
    private long lastClick = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!armed) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return;

        AccessibilityNodeInfo send = null;
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (list != null && !list.isEmpty()) send = list.get(0);
        if (send == null) {
            for (String d : new String[]{"Send", "إرسال"}) {
                List<AccessibilityNodeInfo> l2 = root.findAccessibilityNodeInfosByText(d);
                if (l2 != null) {
                    for (AccessibilityNodeInfo n : l2) {
                        if (n.isClickable() && n.getContentDescription() != null) { send = n; break; }
                    }
                }
                if (send != null) break;
            }
        }
        if (send == null) {
            handleInvalidNumber(root);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClick < 1500) return;

        AccessibilityNodeInfo target = send;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) return;

        lastClick = now;
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        armed = false;

        final PluginCall call = pendingCall;
        pendingCall = null;
        final Context ctx = getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent back = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (back != null) {
                    back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    ctx.startActivity(back);
                }
            } catch (Exception ignored) {}
            if (call != null) call.resolve();
        }, 1200);
    }

    private boolean handleInvalidNumber(AccessibilityNodeInfo root) {
        boolean invalid = false;
        String[] markers = {
            "isn't on WhatsApp", "ليس في واتساب", "ليس على واتساب",
            "Invite to WhatsApp", "دعوة لاستخدام واتساب"
        };
        for (String m : markers) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(m);
            if (l != null && !l.isEmpty()) { invalid = true; break; }
        }
        if (!invalid) return false;

        AccessibilityNodeInfo cancel = null;
        for (String c : new String[]{"Cancel", "إلغاء", "الغاء"}) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(c);
            if (l != null) {
                for (AccessibilityNodeInfo n : l) {
                    if (n.isClickable()) { cancel = n; break; }
                }
            }
            if (cancel != null) break;
        }
        if (cancel == null) return false;

        long now = System.currentTimeMillis();
        if (now - lastClick < 1500) return true;
        lastClick = now;
        cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        armed = false;

        final PluginCall call = pendingCall;
        pendingCall = null;
        final Context ctx = getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent back = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (back != null) {
                    back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    ctx.startActivity(back);
                }
            } catch (Exception ignored) {}
            if (call != null) call.reject("not on whatsapp", "NOT_FOUND");
        }, 800);
        return true;
    }

    @Override
    public void onInterrupt() {}
}
