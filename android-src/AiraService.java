package com.doorbutik.app;

import android.animation.ObjectAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.getcapacitor.JSObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.TreeMap;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

public class AiraService extends Service {
    static AiraService I;
    static volatile boolean running = false;
    static volatile boolean appFg = true;
    static final TreeMap<Integer, String> pending = new TreeMap<>();
    static int seq = 0;

    final Handler ui = new Handler(Looper.getMainLooper());
    Model model;
    Model modelEn;
    Recognizer recHi;
    Recognizer recEn;
    Thread loop;
    volatile boolean loopRun = false;
    volatile boolean paused = false;
    volatile boolean needReset = false;
    TextToSpeech tts;
    boolean ttsOk = false;
    boolean stopping = false;
    Runnable curDone;
    Runnable safety;

    WindowManager wm;
    LinearLayout bubble;
    TextView circle;
    TextView label;
    WindowManager.LayoutParams lp;
    String lineT = "";
    String lineH = "";

    static void ack(int s) {
        synchronized (pending) {
            pending.headMap(s, true).clear();
        }
    }

    static void setAppFg(boolean fg) {
        appFg = fg;
        final AiraService s = I;
        if (s != null) {
            s.ui.post(new Runnable() {
                @Override
                public void run() {
                    if (appFg) s.hideBubble();
                    else s.showBubble();
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            JSObject o = new JSObject();
            AiraPlugin.emit("airaStopped", o);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        I = this;
        running = true;
        startFg();
        initTts();
        new Thread(new Runnable() {
            @Override
            public void run() {
                loadModel();
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("aira", "आयरा", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, fl);
        Intent stop = new Intent(this, AiraService.class);
        stop.setAction("STOP");
        PendingIntent ps = PendingIntent.getService(this, 1, stop, fl);
        Notification n = new NotificationCompat.Builder(this, "aira")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("आयरा सुन रही है")
            .setContentText("नाप बोलिए — ऐप खोलने के लिए टैप करें")
            .setContentIntent(pi)
            .addAction(0, "बंद करें", ps)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) return;
                int r = tts.setLanguage(new Locale("hi", "IN"));
                ttsOk = r >= TextToSpeech.LANG_AVAILABLE;
                if (!ttsOk) {
                    JSObject o = new JSObject();
                    AiraPlugin.emit("airaTts", o);
                    return;
                }
                tts.setPitch(1.15f);
                tts.setSpeechRate(0.95f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {}

                    @Override
                    public void onDone(String id) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                finishSay();
                            }
                        });
                    }

                    @Override
                    public void onError(String id) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                finishSay();
                            }
                        });
                    }
                });
            }
        });
    }

    void say(final String text, final float pitch, final float rate, final String voiceName, final Runnable done) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (curDone != null) finishSay();
                if (!ttsOk || tts == null) {
                    done.run();
                    return;
                }
                curDone = done;
                paused = true;
                try {
                    tts.setPitch(pitch > 0 ? pitch : 1.1f);
                    tts.setSpeechRate(rate > 0 ? rate : 0.95f);
                    if (voiceName != null && !voiceName.isEmpty()) {
                        for (Voice v : tts.getVoices()) {
                            if (v.getName().equals(voiceName)) {
                                tts.setVoice(v);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
                safety = new Runnable() {
                    @Override
                    public void run() {
                        finishSay();
                    }
                };
                ui.postDelayed(safety, Math.max(4000L, text.length() * 260L));
            }
        });
    }

    void finishSay() {
        final Runnable d = curDone;
        if (d == null) return;
        curDone = null;
        if (safety != null) ui.removeCallbacks(safety);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!stopping) {
                    needReset = true;
                    paused = false;
                }
            }
        }, 350);
        d.run();
    }

    private void copyAssets(String src, File dst) throws IOException {
        String[] list = getAssets().list(src);
        if (list != null && list.length > 0) {
            dst.mkdirs();
            for (String n : list) copyAssets(src + "/" + n, new File(dst, n));
        } else {
            File p = dst.getParentFile();
            if (p != null) p.mkdirs();
            InputStream in = getAssets().open(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.close();
            in.close();
        }
    }

    private File ensureModel(String name, boolean required) throws IOException {
        File dir = new File(getFilesDir(), name);
        File ok = new File(dir, ".ok");
        if (!ok.exists()) {
            String[] l = getAssets().list(name);
            if (l == null || l.length == 0) {
                if (required) throw new IOException(name + " missing");
                return null;
            }
            copyAssets(name, dir);
            ok.createNewFile();
        }
        return dir;
    }

    private void loadModel() {
        try {
            File hi = ensureModel("model-hi", true);
            File en = null;
            try {
                en = ensureModel("model-en", false);
            } catch (Exception e) {
                en = null;
            }
            model = new Model(hi.getAbsolutePath());
            recHi = new Recognizer(model, 16000.0f);
            if (en != null) {
                try {
                    modelEn = new Model(en.getAbsolutePath());
                    recEn = new Recognizer(modelEn, 16000.0f);
                } catch (Exception e) {
                    recEn = null;
                }
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (stopping) return;
                    startLoop();
                    JSObject o = new JSObject();
                    o.put("en", recEn != null);
                    AiraPlugin.emit("airaReady", o);
                    if (!appFg) showBubble();
                }
            });
        } catch (final Exception e) {
            JSObject o = new JSObject();
            o.put("msg", String.valueOf(e.getMessage()));
            AiraPlugin.emit("airaError", o);
        }
    }

    private void startLoop() {
        loopRun = true;
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                int bs = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord ar = null;
                try {
                    ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(bs * 2, 32000));
                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                        JSObject o = new JSObject();
                        o.put("msg", "माइक चालू नहीं हो पाया");
                        AiraPlugin.emit("airaError", o);
                        return;
                    }
                    ar.startRecording();
                    short[] b = new short[2000];
                    String lastP = "";
                    while (loopRun) {
                        int n = ar.read(b, 0, b.length);
                        if (n <= 0) continue;
                        if (paused) continue;
                        if (needReset) {
                            needReset = false;
                            recHi.reset();
                            if (recEn != null) recEn.reset();
                            lastP = "";
                        }
                        boolean a = recHi.acceptWaveForm(b, n);
                        boolean e = recEn != null && recEn.acceptWaveForm(b, n);
                        if (a || e) {
                            String h = field(a ? recHi.getResult() : recHi.getFinalResult(), "text");
                            String en = "";
                            if (recEn != null) en = field(e ? recEn.getResult() : recEn.getFinalResult(), "text");
                            lastP = "";
                            deliver(h, en);
                        } else {
                            String p = field(recHi.getPartialResult(), "partial");
                            if (p.isEmpty() && recEn != null) p = field(recEn.getPartialResult(), "partial");
                            if (!p.isEmpty() && !p.equals(lastP)) {
                                lastP = p;
                                partial(p);
                            }
                        }
                    }
                } catch (Exception ex) {
                    JSObject o = new JSObject();
                    o.put("msg", String.valueOf(ex.getMessage()));
                    AiraPlugin.emit("airaError", o);
                } finally {
                    try {
                        if (ar != null) {
                            ar.stop();
                            ar.release();
                        }
                    } catch (Exception ex) {}
                }
            }
        });
        loop.start();
    }

    private String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void deliver(final String hi, final String en) {
        if ((hi.isEmpty() && en.isEmpty()) || stopping) return;
        int s;
        synchronized (pending) {
            s = ++seq;
            pending.put(s, hi + "\u0001" + en);
        }
        JSObject o = new JSObject();
        o.put("seq", s);
        o.put("text", hi);
        o.put("alt", en);
        AiraPlugin.emit("airaText", o);
        if (!appFg) setLines(null, "सुना: " + (hi.isEmpty() ? en : hi));
    }

    private void partial(String t) {
        if (stopping) return;
        if (!appFg) setLines(null, "… " + t);
        JSObject o = new JSObject();
        o.put("text", t);
        AiraPlugin.emit("airaPartial", o);
    }

    /* ---------- floating bubble ---------- */
    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    void setLines(final String t, final String h) {
        if (t != null) lineT = t;
        if (h != null) lineH = h;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (label == null) return;
                String s = lineT;
                if (!lineH.isEmpty()) s = s.isEmpty() ? lineH : s + "\n" + lineH;
                label.setText(s);
                label.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    void showBubble() {
        if (bubble != null || stopping || !running) return;
        if (!Settings.canDrawOverlays(this)) return;
        try {
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setGravity(Gravity.CENTER_HORIZONTAL);

            circle = new TextView(this);
            circle.setText("🎙");
            circle.setTextSize(26);
            circle.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF5A1157);
            g.setStroke(dp(3), 0xFFFFB800);
            circle.setBackground(g);
            bubble.addView(circle, new LinearLayout.LayoutParams(dp(62), dp(62)));

            label = new TextView(this);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(12);
            label.setPadding(dp(10), dp(5), dp(10), dp(5));
            label.setMaxWidth(dp(230));
            label.setMaxLines(3);
            GradientDrawable g2 = new GradientDrawable();
            g2.setCornerRadius(dp(14));
            g2.setColor(0xE65A1157);
            label.setBackground(g2);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ll.topMargin = dp(4);
            bubble.addView(label, ll);
            label.setVisibility(View.GONE);

            int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(8);
            lp.y = dp(200);

            circle.setOnTouchListener(new View.OnTouchListener() {
                int sx, sy;
                float tx, ty;
                boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = lp.x;
                            sy = lp.y;
                            tx = e.getRawX();
                            ty = e.getRawY();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                            if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                            if (moved) {
                                lp.x = sx + (int) dx;
                                lp.y = sy + (int) dy;
                                try {
                                    wm.updateViewLayout(bubble, lp);
                                } catch (Exception ex) {}
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved) openApp();
                            return true;
                    }
                    return false;
                }
            });
            wm.addView(bubble, lp);
            ObjectAnimator ax = ObjectAnimator.ofFloat(circle, "scaleX", 1f, 1.12f);
            ObjectAnimator ay = ObjectAnimator.ofFloat(circle, "scaleY", 1f, 1.12f);
            for (ObjectAnimator a : new ObjectAnimator[] { ax, ay }) {
                a.setDuration(800);
                a.setRepeatCount(ObjectAnimator.INFINITE);
                a.setRepeatMode(ObjectAnimator.REVERSE);
                a.start();
            }
            setLines(null, null);
        } catch (Exception e) {
            bubble = null;
        }
    }

    void hideBubble() {
        if (bubble == null) return;
        try {
            wm.removeView(bubble);
        } catch (Exception e) {}
        bubble = null;
        circle = null;
        label = null;
    }

    private void openApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception e) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        ui.removeCallbacksAndMessages(null);
        hideBubble();
        loopRun = false;
        final Thread lp = loop;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (lp != null) lp.join(2000);
                } catch (Exception e) {}
                try {
                    if (recHi != null) recHi.close();
                    if (recEn != null) recEn.close();
                    if (model != null) model.close();
                    if (modelEn != null) modelEn.close();
                } catch (Exception e) {}
            }
        }).start();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception e) {}
        final Runnable d = curDone;
        curDone = null;
        if (d != null) d.run();
        I = null;
        super.onDestroy();
    }
}
