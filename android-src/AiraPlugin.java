package com.doorbutik.app;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.Map;

@CapacitorPlugin(
    name = "Aira",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic"),
        @Permission(strings = { Manifest.permission.CAMERA }, alias = "camera"),
        @Permission(strings = { "android.permission.POST_NOTIFICATIONS" }, alias = "notif"),
        @Permission(strings = { "android.permission.READ_MEDIA_IMAGES" }, alias = "media")
    }
)
public class AiraPlugin extends Plugin {
    static AiraPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    static void emit(String name, JSObject data) {
        if (inst != null) inst.notifyListeners(name, data, true);
    }

    private boolean hasModel() {
        try {
            String[] l = getContext().getAssets().list("model-hi");
            return l != null && l.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private JSObject statusObj() {
        JSObject o = new JSObject();
        o.put("mic", getPermissionState("mic") == PermissionState.GRANTED);
        o.put("camera", getPermissionState("camera") == PermissionState.GRANTED);
        o.put("notif", android.os.Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED);
        o.put("media", android.os.Build.VERSION.SDK_INT < 33 || getPermissionState("media") == PermissionState.GRANTED);
        android.os.PowerManager pm = (android.os.PowerManager) getContext().getSystemService(android.content.Context.POWER_SERVICE);
        o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        o.put("overlay", Settings.canDrawOverlays(getContext()));
        o.put("model", hasModel());
        o.put("running", AiraService.running);
        return o;
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void requestMic(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            call.resolve(statusObj());
        } else {
            requestPermissionForAlias("mic", call, "micCb");
        }
    }

    @PermissionCallback
    private void micCb(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void requestAll(PluginCall call) {
        requestPermissionForAliases(new String[] { "mic", "camera", "notif", "media" }, call, "micCb");
    }

    @PluginMethod
    public void requestBattery(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(i);
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(i);
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), AiraService.class);
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("start failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            getContext().stopService(new Intent(getContext(), AiraService.class));
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }

    private android.speech.tts.TextToSpeech tts2;
    private boolean tts2Ready = false;
    private Runnable tts2Done;

    private void ensureTts(final Runnable onReady) {
        if (tts2Ready) {
            onReady.run();
            return;
        }
        tts2 = new android.speech.tts.TextToSpeech(getContext(), new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts2.setLanguage(new java.util.Locale("hi", "IN"));
                    tts2.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {}

                        @Override
                        public void onDone(String id) {
                            Runnable d = tts2Done;
                            tts2Done = null;
                            if (d != null) d.run();
                        }

                        @Override
                        public void onError(String id) {
                            onDone(id);
                        }
                    });
                    tts2Ready = true;
                    onReady.run();
                }
            }
        });
    }

    @PluginMethod
    public void ttsSettings(final PluginCall call) {
        try {
            android.content.Intent i = new android.content.Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                android.content.Intent i2 = new android.content.Intent(android.provider.Settings.ACTION_SETTINGS);
                i2.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i2);
            } catch (Exception e2) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void voices(final PluginCall call) {
        ensureTts(new Runnable() {
            @Override
            public void run() {
                JSArray arr = new JSArray();
                try {
                    for (android.speech.tts.Voice v : tts2.getVoices()) {
                        if ("hi".equals(v.getLocale().getLanguage())) {
                            JSObject o = new JSObject();
                            o.put("name", v.getName());
                            o.put("offline", !v.isNetworkConnectionRequired());
                            arr.put(o);
                        }
                    }
                } catch (Exception e) {}
                JSObject r = new JSObject();
                r.put("voices", arr);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void say(final PluginCall call) {
        final String text = call.getString("text", "");
        final float pitch = call.getFloat("pitch", 0f);
        final float rate = call.getFloat("rate", 0f);
        final String voice = call.getString("voice", "");
        if (text.isEmpty()) {
            call.resolve();
            return;
        }
        AiraService s = AiraService.I;
        if (s != null) {
            s.say(text, pitch, rate, voice, new Runnable() {
                @Override
                public void run() {
                    call.resolve();
                }
            });
            return;
        }
        ensureTts(new Runnable() {
            @Override
            public void run() {
                try {
                    tts2.setPitch(pitch > 0 ? pitch : 1.1f);
                    tts2.setSpeechRate(rate > 0 ? rate : 0.95f);
                    if (!voice.isEmpty()) {
                        for (android.speech.tts.Voice v : tts2.getVoices()) {
                            if (v.getName().equals(voice)) {
                                tts2.setVoice(v);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}
                tts2Done = new Runnable() {
                    @Override
                    public void run() {
                        call.resolve();
                    }
                };
                tts2.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "t" + System.nanoTime());
            }
        });
    }

    @PluginMethod
    public void bubble(PluginCall call) {
        AiraService s = AiraService.I;
        if (s != null) s.setLines(call.getString("t"), call.getString("h"));
        call.resolve();
    }

    @PluginMethod
    public void ack(PluginCall call) {
        Integer seq = call.getInt("seq");
        if (seq != null) AiraService.ack(seq);
        call.resolve();
    }

    @PluginMethod
    public void flush(PluginCall call) {
        JSObject o = new JSObject();
        JSArray arr = new JSArray();
        synchronized (AiraService.pending) {
            for (Map.Entry<Integer, String> e : AiraService.pending.entrySet()) {
                JSObject it = new JSObject();
                it.put("seq", e.getKey());
                String[] pr = e.getValue().split("\u0001", -1);
                it.put("text", pr[0]);
                it.put("alt", pr.length > 1 ? pr[1] : "");
                arr.put(it);
            }
        }
        o.put("items", arr);
        call.resolve(o);
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        AiraService.setAppFg(false);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        AiraService.setAppFg(true);
    }
}
