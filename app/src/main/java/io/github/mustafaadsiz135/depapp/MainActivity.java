package io.github.mustafaadsiz135.depapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements SeismoService.Listener {

    private TextView statusText;
    private TextView liveText;
    private TextView sensLabel;
    private TextView logText;
    private TextView sensorWarn;
    private SeismoGraphView graph;
    private Button btnStart;
    private long lastTextUpdate = 0;
    private int lastState = -1;
    private AlertDialog alarmDialog;
    private boolean hasAccel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        liveText = findViewById(R.id.liveText);
        sensLabel = findViewById(R.id.sensLabel);
        logText = findViewById(R.id.logText);
        sensorWarn = findViewById(R.id.sensorWarn);
        graph = findViewById(R.id.graph);
        btnStart = findViewById(R.id.btnStart);

        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        hasAccel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
        boolean hasGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        if (!hasAccel) {
            sensorWarn.setText(R.string.no_accel);
            sensorWarn.setVisibility(View.VISIBLE);
        } else if (!hasGyro) {
            sensorWarn.setText(R.string.no_gyro);
            sensorWarn.setVisibility(View.VISIBLE);
        }

        SeekBar seek = findViewById(R.id.sensSeek);
        int level = AppPrefs.getSensitivity(this);
        seek.setMax(4);
        seek.setProgress(level - 1);
        sensLabel.setText(getString(R.string.sensitivity_label, level));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                sensLabel.setText(getString(R.string.sensitivity_label, p + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                AppPrefs.setSensitivity(MainActivity.this, s.getProgress() + 1);
                if (SeismoService.isRunning()) send(SeismoService.ACTION_UPDATE_SETTINGS);
            }
        });

        Switch sound = findViewById(R.id.soundSwitch);
        sound.setChecked(AppPrefs.isSoundOn(this));
        sound.setOnCheckedChangeListener((b, checked) -> AppPrefs.setSoundOn(this, checked));

        btnStart.setOnClickListener(v -> {
            if (SeismoService.isRunning()) {
                stopService(new Intent(this, SeismoService.class));
                showStopped();
                btnStart.postDelayed(this::refreshButton, 300);
            } else {
                send(SeismoService.ACTION_START);
                btnStart.postDelayed(this::refreshButton, 300);
            }
        });

        findViewById(R.id.btnSimulate).setOnClickListener(v -> {
            send(SeismoService.ACTION_SIMULATE);
            Toast.makeText(this, R.string.sim_started, Toast.LENGTH_LONG).show();
            btnStart.postDelayed(this::refreshButton, 300);
        });

        findViewById(R.id.btnTest).setOnClickListener(v -> {
            send(SeismoService.ACTION_TEST_ALARM);
            btnStart.postDelayed(this::refreshButton, 300);
        });

        findViewById(R.id.btnAbout).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_text)
                .setPositiveButton(R.string.ok, null)
                .show());

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            AppPrefs.clearLog(this);
            refreshLog();
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SeismoService.setListener(this);
        refreshButton();
        refreshLog();
        if (!SeismoService.isRunning()) showStopped();
    }

    @Override
    protected void onPause() {
        SeismoService.setListener(null);
        super.onPause();
    }

    private void send(String action) {
        if (!hasAccel) {
            Toast.makeText(this, R.string.no_accel, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, SeismoService.class).setAction(action);
        if (SeismoService.isRunning()) startService(i);
        else startForegroundService(i);
    }

    private void refreshButton() {
        btnStart.setText(SeismoService.isRunning() ? R.string.btn_stop : R.string.btn_start);
    }

    private void showStopped() {
        lastState = -1;
        statusText.setText(R.string.status_stopped);
        statusText.setBackgroundColor(Color.parseColor("#546E7A"));
        liveText.setText("");
        graph.clear();
    }

    private void refreshLog() {
        String[] lines = AppPrefs.getLog(this);
        if (lines.length == 0) {
            logText.setText(R.string.log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(lines[i]);
        }
        logText.setText(sb.toString());
    }

    // ---------- Servisten gelen veriler ----------

    @Override
    public void onSample(float lx, float ly, float lz, int state, float sta, float lta,
                         float ratio, float gyro, float threshold) {
        graph.add(lx, ly, lz, state, threshold);
        if (state != lastState) {
            lastState = state;
            applyState(state);
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastTextUpdate > 200) {
            lastTextUpdate = now;
            liveText.setText(String.format(Locale.getDefault(),
                    getString(R.string.live_values), sta, lta, ratio, gyro, threshold));
            if (!btnStart.getText().toString().equals(getString(R.string.btn_stop))) refreshButton();
        }
    }

    private void applyState(int state) {
        int text;
        String color;
        switch (state) {
            case QuakeDetector.STATE_MONITORING:
                text = R.string.status_monitoring; color = "#2E7D32"; break;
            case QuakeDetector.STATE_UNSTABLE:
                text = R.string.status_unstable; color = "#EF6C00"; break;
            case QuakeDetector.STATE_SUSPICIOUS:
                text = R.string.status_suspicious; color = "#F9A825"; break;
            case QuakeDetector.STATE_QUAKE:
                text = R.string.status_quake; color = "#C62828"; break;
            default:
                text = R.string.status_calibrating; color = "#607D8B"; break;
        }
        statusText.setText(text);
        statusText.setBackgroundColor(Color.parseColor(color));
    }

    @Override
    public void onAlarm(String summary, boolean test) {
        if (isFinishing()) return;
        if (alarmDialog != null && alarmDialog.isShowing()) alarmDialog.dismiss();
        alarmDialog = new AlertDialog.Builder(this)
                .setTitle(test ? R.string.test_alarm_title : R.string.alarm_title)
                .setMessage(summary + "\n\n" + getString(R.string.dialog_quake_msg))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    public void onEventLogged() {
        refreshLog();
    }
}
