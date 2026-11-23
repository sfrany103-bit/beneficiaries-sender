package com.beneficiaries.sender;

import android.Manifest;
import android.os.Build;
import android.telephony.SmsManager;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.util.ArrayList;

@CapacitorPlugin(name = "SmsSender", permissions = {
    @Permission(strings = {Manifest.permission.SEND_SMS}, alias = "sms")
})
public class SmsSenderPlugin extends Plugin {
  @PluginMethod
  public void send(PluginCall call) {
    String number = call.getString("number");
    String text = call.getString("text");
    if (number == null || text == null) { call.reject("missing data"); return; }
    if (getPermissionState("sms") != PermissionState.GRANTED) { call.reject("no permission"); return; }
    try {
      SmsManager sm = Build.VERSION.SDK_INT >= 31
          ? getContext().getSystemService(SmsManager.class)
          : SmsManager.getDefault();
      ArrayList<String> parts = sm.divideMessage(text);
      sm.sendMultipartTextMessage(number, null, parts, null, null);
      call.resolve();
    } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
  }
}
