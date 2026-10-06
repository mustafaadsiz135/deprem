package io.github.mustafaadsiz135.depapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Arka planda (ekran kapalıyken de) sensörleri dinleyen ön plan servisi.
 */
public class SeismoService extends Service implements SensorEventListener {

    public static final String ACTION_START = "depapp.START";
    public static final String ACTION_STOP = "depapp.STOP";
    public static final String ACTION_SIMULATE = "depapp.SIMULATE";
    public static final String ACTION_TEST_ALARM = "depapp.TEST_ALARM";
    public static final String ACTION_UPDATE_SETTINGS = "depapp.UPDATE_SETTINGS";

    private static final String CH_MONITOR = "monitor";
    private static final String CH_ALARM = "alarm";
    private static final int NOTIF_ID = 1;
    private static final int ALARM_NOTIF_ID = 2;
    private static final int SAMPLE_US = 20000; // 50 Hz
    private static final double ALARM_COOLDOWN = 30.0;

    public interface Listener {
        void onSample(float lx, float ly, float lz, int state, float sta, float lta,
                      float ratio, float gyro, float threshold);
        void onAlarm(String summary, boolean test);
        void onEventLogged();
    }

    private static volatile Listener listener;
    private static volatile boolean running = false;

    public static void setListener(Listener l) { listener = l; }
    public static boolean isRunning() { return running; }

    private SensorManager sensorManager;
    private Sensor accel;
    private Sensor gyro;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final QuakeDetector detector = new QuakeDetector();
    private final QuakeSimulator simulator = new QuakeSimulator();
    private volatile boolean simRequested = false;
    private boolean eventIsSim = false;
    private float gyroMag = 0f;
    private double lastAlarmT = -1e9;

    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Son ~80 saniyelik veri (olay olunca CSV olarak kaydedilir)
    private static final int BUF = 4096;
    private final float[] bT = new float[BUF];
    private final float[] bX = new float[BUF];
    private final float[] bY = new float[BUF];
    private final float[] bZ = new float[BUF];
    private final float[] bM = new float[BUF];
    private final float[] bG = new float[BUF];
    private final float[] bR = new float[BUF];
    private int bPos = 0;
    private int bCount = 0;
    private double tZero = -1;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int r = tts.setLanguage(Locale.getDefault());
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(new Locale("tr", "TR"));
                }
                ttsReady = true;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) startMonitoring();
        if (ACTION_SIMULATE.equals(action)) {
            simRequested = true;
        } else if (ACTION_TEST_ALARM.equals(action)) {
            raiseAlarm(getString(R.string.test_alarm_text), true, false);
        } else if (ACTION_UPDATE_SETTINGS.equals(action)) {
            final int level = AppPrefs.getSensitivity(this);
            if (sensorHandler != null) sensorHandler.post(() -> detector.setSensitivity(level));
        }
        return START_STICKY;
    }

    private void startMonitoring() {
        Notification n = buildMonitorNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException ex) {
            // Sistem arka planda yeniden başlatmaya izin vermediyse kapan
            stopSelf();
            return;
        }
        running = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DEPapp:seismo");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        sensorThread = new HandlerThread("seismo-sensors");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        detector.reset();
        detector.setSensitivity(AppPrefs.getSensitivity(this));
        bPos = 0;
        bCount = 0;
        tZero = -1;

        if (accel != null) sensorManager.registerListener(this, accel, SAMPLE_US, sensorHandler);
        if (gyro != null) sensorManager.registerListener(this, gyro, SAMPLE_US, sensorHandler);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (sensorThread != null) sensorThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ---------- Sensör verisi (arka plan iş parçacığında çalışır) ----------

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            gyroMag = (float) Math.sqrt(x * x + y * y + z * z);
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        double t = e.timestamp / 1e9;
        if (tZero < 0) tZero = t;
        double ax = e.values[0], ay = e.values[1], az = e.values[2];

        if (simRequested && detector.getState() != QuakeDetector.STATE_CALIBRATING) {
            simRequested = false;
            simulator.start(t);
        }
        boolean simActive = simulator.isActive(t);
        if (simActive) {
            double[] o = simulator.offset(t);
            ax += o[0];
            ay += o[1];
            az += o[2];
        }

        int ev = detector.feed(t, ax, ay, az, gyroMag);
        record(t);

        if (ev == QuakeDetector.EVT_ALARM) {
            eventIsSim = simActive;
            boolean cooldown = (t - lastAlarmT) < ALARM_COOLDOWN;
            lastAlarmT = t;
            final String mmi = QuakeDetector.toRoman(QuakeDetector.estimateMmi(detector.getCurrentPeak()));
            final boolean sim = eventIsSim;
            final boolean silent = cooldown;
            main.post(() -> raiseAlarm(getString(R.string.alarm_text, mmi), false, silent || !running, sim));
        } else if (ev == QuakeDetector.EVT_END) {
            onEventEnded(t);
        }

        final float lx = (float) detector.getLinX();
        final float ly = (float) detector.getLinY();
        final float lz = (float) detector.getLinZ();
        final int st = detector.getState();
        final float sta = (float) detector.getSta();
        final float lta = (float) detector.getLta();
        final float ratio = (float) detector.getRatio();
        final float g = (float) detector.getGyro();
        final float thr = (float) detector.getMinAmp();
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onSample(lx, ly, lz, st, sta, lta, ratio, g, thr);
        });
    }

    private void record(double t) {
        bT[bPos] = (float) (t - tZero);
        bX[bPos] = (float) detector.getLinX();
        bY[bPos] = (float) detector.getLinY();
        bZ[bPos] = (float) detector.getLinZ();
        bM[bPos] = (float) detector.getMag();
        bG[bPos] = (float) detector.getGyro();
        bR[bPos] = (float) detector.getRatio();
        bPos = (bPos + 1) % BUF;
        if (bCount < BUF) bCount++;
    }

    private void onEventEnded(double t) {
        final double pga = detector.getEventPeak();
        final double dur = detector.getEventDuration();
        final boolean sim = eventIsSim;
        final Date now = new Date();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now);
        final String fileName = "deprem_" + stamp + (sim ? "_sim" : "") + ".csv";

        // Tamponu kopyala, dosyayı ayrı iş parçacığında yaz
        final int cnt = bCount;
        final int start = (bPos - cnt + BUF) % BUF;
        final float[][] cols = new float[7][cnt];
        for (int i = 0; i < cnt; i++) {
            int j = (start + i) % BUF;
            cols[0][i] = bT[j];
            cols[1][i] = bX[j];
            cols[2][i] = bY[j];
            cols[3][i] = bZ[j];
            cols[4][i] = bM[j];
            cols[5][i] = bG[j];
            cols[6][i] = bR[j];
        }
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            String saved = writeCsv(ctx, fileName, cols);
            String when = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(now);
            String tag = sim ? ctx.getString(R.string.sim_tag) : "";
            double mmi = QuakeDetector.estimateMmi(pga);
            String line = ctx.getString(R.string.log_line, when, tag, pga, pga / 9.81 * 100.0, dur,
                    QuakeDetector.toRoman(mmi), saved != null ? saved : "-");
            AppPrefs.addLog(ctx, line);
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onEventLogged();
            });
        }).start();
    }

    private static String writeCsv(Context ctx, String name, float[][] cols) {
        File dir = ctx.getExternalFilesDir("kayitlar");
        if (dir == null) dir = new File(ctx.getFilesDir(), "kayitlar");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, name);
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            w.println("t_s,lin_x,lin_y,lin_z,lin_mag,gyro_rad_s,sta_lta");
            int n = cols[0].length;
            for (int i = 0; i < n; i++) {
                w.println(String.format(Locale.US, "%.3f,%.4f,%.4f,%.4f,%.4f,%.3f,%.2f",
                        cols[0][i], cols[1][i], cols[2][i], cols[3][i], cols[4][i], cols[5][i], cols[6][i]));
            }
            return name;
        } catch (IOException ex) {
            return null;
        }
    }

    // ---------- Alarm (ana iş parçacığında çalışır) ----------

    private void raiseAlarm(String text, boolean test, boolean silent) {
        raiseAlarm(text, test, silent, false);
    }

    private void raiseAlarm(String text, boolean test, boolean silent, boolean sim) {
        String title = getString(test ? R.string.test_alarm_title : R.string.alarm_title);
        if (sim) title = title + " " + getString(R.string.sim_tag);
        Notification.Builder b = new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_quake)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(ALARM_NOTIF_ID, b.build());

        if (!silent && AppPrefs.isSoundOn(this)) {
            playAlarm(test);
        }
        Listener l = listener;
        if (l != null) l.onAlarm(text, test);
    }

    private void playAlarm(boolean test) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2500);
            main.postDelayed(tg::release, 2800);
        } catch (RuntimeException ignored) {
            // Bazı cihazlarda ses üreteci açılamayabilir
        }
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 250, 500, 250, 500}, -1));
        }
        final String speech = getString(test ? R.string.test_speech : R.string.alarm_speech);
        main.postDelayed(() -> speak(speech, true), 2700);
        main.postDelayed(() -> speak(speech, false), 2750);
    }

    private void speak(String text, boolean flush) {
        if (tts == null || !ttsReady) return;
        Bundle p = new Bundle();
        p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, p, "depapp" + System.nanoTime());
    }

    // ---------- Bildirimler ----------

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel mon = new NotificationChannel(CH_MONITOR,
                getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_LOW);
        mon.setShowBadge(false);
        NotificationChannel al = new NotificationChannel(CH_ALARM,
                getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        al.setSound(null, null); // sesi uygulama kendisi çalar
        al.enableVibration(false);
        al.setBypassDnd(true);
        nm.createNotificationChannel(mon);
        nm.createNotificationChannel(al);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildMonitorNotification() {
        Intent stop = new Intent(this, SeismoService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                null, getString(R.string.notif_stop), stopPi).build();
        return new Notification.Builder(this, CH_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_quake)
                .setContentTitle(getString(R.string.notif_monitor_title))
                .setContentText(getString(R.string.notif_monitor_text))
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .addAction(stopAction)
                .build();
    }
}
