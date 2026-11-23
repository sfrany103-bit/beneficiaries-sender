package com.beneficiaries.sender;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.getcapacitor.PluginCall;

import java.util.List;

public class WhatsAppAccessibilityService extends AccessibilityService {

    public static volatile PluginCall pendingCall = null;
    public static volatile boolean armed = false;
    public static volatile boolean manualMode = false;
    public static volatile boolean reuseDraft = false;
    public static volatile String pendingText = null;
    public static volatile long entrySeen = 0;
    private long lastClick = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!armed) return;

        if (manualMode && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null && isSendNode(src)) {
                finish(true, null, 700);
                return;
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return;

        AccessibilityNodeInfo send = findSend(root, pkg);
        if (send == null) {
            if (handleInvalidNumber(root)) return;
            if (reuseDraft && entrySeen == 0 && findEntry(root, pkg) != null) {
                entrySeen = System.currentTimeMillis();
                handler.postDelayed(this::checkDraft, 2000);
            }
            return;
        }
        if (manualMode) return;

        long now = System.currentTimeMillis();
        if (now - lastClick < 1500) return;

        AccessibilityNodeInfo target = send;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) return;

        lastClick = now;
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        finish(true, null, 1200);
    }

    private void checkDraft() {
        if (!armed || !reuseDraft || pendingText == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { entrySeen = 0; return; }
        CharSequence pkg = root.getPackageName();
        if (pkg == null) { entrySeen = 0; return; }
        if (findSend(root, pkg) != null) return;
        AccessibilityNodeInfo entry = findEntry(root, pkg);
        if (entry == null) { entrySeen = 0; return; }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingText);
        entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        reuseDraft = false;
    }

    private AccessibilityNodeInfo findEntry(AccessibilityNodeInfo root, CharSequence pkg) {
        List<AccessibilityNodeInfo> e = root.findAccessibilityNodeInfosByViewId(pkg + ":id/entry");
        return (e != null && !e.isEmpty()) ? e.get(0) : null;
    }

    private AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, CharSequence pkg) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (list != null && !list.isEmpty()) return list.get(0);
        for (String d : new String[]{"Send", "إرسال"}) {
            List<AccessibilityNodeInfo> l2 = root.findAccessibilityNodeInfosByText(d);
            if (l2 != null) {
                for (AccessibilityNodeInfo n : l2) {
                    if (n.isClickable() && n.getContentDescription() != null) return n;
                }
            }
        }
        return null;
    }

    private boolean isSendNode(AccessibilityNodeInfo n) {
        for (int depth = 0; n != null && depth < 3; depth++) {
            String id = n.getViewIdResourceName();
            if (id != null && id.endsWith(":id/send")) return true;
            CharSequence d = n.getContentDescription();
            if (d != null) {
                String s = d.toString();
                if (s.equals("Send") || s.equals("إرسال")) return true;
            }
            n = n.getParent();
        }
        return false;
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
        finish(false, "not on whatsapp", 800);
        return true;
    }

    private void finish(final boolean ok, final String reason, long delay) {
        armed = false;
        manualMode = false;
        reuseDraft = false;
        pendingText = null;
        entrySeen = 0;
        final PluginCall call = pendingCall;
        pendingCall = null;
        final Context ctx = getApplicationContext();
        handler.postDelayed(() -> {
            try {
                Intent back = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (back != null) {
                    back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    ctx.startActivity(back);
                }
            } catch (Exception ignored) {}
            if (call != null) {
                if (ok) call.resolve();
                else call.reject(reason, "NOT_FOUND");
            }
        }, delay);
    }

    @Override
    public void onInterrupt() {}
}
