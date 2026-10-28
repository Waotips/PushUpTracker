package com.example.pushuptracker;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String CHANNEL_ID = "pushup_goal_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    // Коэффициент низкочастотного фильтра для отделения гравитации от движения.
    private static final float GRAVITY_FILTER_ALPHA = 0.85f;

    // Минимальная пауза между засчитанными повторениями, чтобы один удар не считался несколько раз.
    private static final long MIN_REP_INTERVAL_MS = 900;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private TextView tvCount;
    private TextView tvGoal;
    private TextView tvStatus;
    private TextView tvSensor;
    private TextView tvThreshold;
    private EditText etGoal;
    private Button btnStart;

    private boolean tracking = false;
    private int pushUpCount = 0;
    private int goal = 10;
    private float threshold = 2.5f;

    private final float[] gravity = new float[3];
    private float smoothedMotion = 0f;
    private long lastRepTime = 0L;
    private long lastUiUpdateTime = 0L;
    private boolean goalNotificationShown = false;

    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences("pushup_tracker", MODE_PRIVATE);
        goal = preferences.getInt("goal", 10);
        pushUpCount = preferences.getInt("count", 0);
        threshold = preferences.getFloat("threshold", 2.5f);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        initViews();
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();
        updateUi();
    }

    private void initViews() {
        tvCount = findViewById(R.id.tvCount);
        tvGoal = findViewById(R.id.tvGoal);
        tvStatus = findViewById(R.id.tvStatus);
        tvSensor = findViewById(R.id.tvSensor);
        tvThreshold = findViewById(R.id.tvThreshold);
        etGoal = findViewById(R.id.etGoal);
        btnStart = findViewById(R.id.btnStart);
        Button btnReset = findViewById(R.id.btnReset);
        Button btnSaveGoal = findViewById(R.id.btnSaveGoal);
        SeekBar seekThreshold = findViewById(R.id.seekThreshold);

        etGoal.setText(String.valueOf(goal));
        seekThreshold.setProgress(Math.round((threshold - 1.0f) * 10f));

        if (accelerometer == null) {
            tvSensor.setText("Акселерометр не найден. Приложение не сможет считать отжимания.");
            btnStart.setEnabled(false);
        } else {
            tvSensor.setText("Акселерометр найден: " + accelerometer.getName());
        }

        btnStart.setOnClickListener(v -> {
            if (tracking) {
                stopTracking();
            } else {
                startTracking();
            }
        });

        btnReset.setOnClickListener(v -> {
            pushUpCount = 0;
            goalNotificationShown = false;
            saveState();
            updateUi();
            tvStatus.setText("Статус: счетчик сброшен");
        });

        btnSaveGoal.setOnClickListener(v -> saveGoalFromInput());

        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                threshold = 1.0f + progress / 10.0f; // диапазон 1.0–6.0 м/с²
                preferences.edit().putFloat("threshold", threshold).apply();
                updateThresholdText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        updateThresholdText();
    }

    private void saveGoalFromInput() {
        String value = etGoal.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "Введите цель", Toast.LENGTH_SHORT).show();
            return;
        }

        int parsedGoal;
        try {
            parsedGoal = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Цель должна быть числом", Toast.LENGTH_SHORT).show();
            return;
        }

        if (parsedGoal <= 0) {
            Toast.makeText(this, "Цель должна быть больше 0", Toast.LENGTH_SHORT).show();
            return;
        }

        goal = parsedGoal;
        goalNotificationShown = pushUpCount >= goal;
        saveState();
        updateUi();
        Toast.makeText(this, "Цель сохранена", Toast.LENGTH_SHORT).show();
    }

    private void startTracking() {
        if (accelerometer == null) return;

        tracking = true;
        smoothedMotion = 0f;
        Arrays.fill(gravity, 0f);
        lastRepTime = 0L;
        lastUiUpdateTime = 0L;

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        btnStart.setText("Пауза");
        tvStatus.setText("Статус: идет подсчет. Выполняйте отжимания.");
    }

    private void stopTracking() {
        tracking = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        btnStart.setText("Старт");
        tvStatus.setText("Статус: пауза");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tracking && accelerometer != null && sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!tracking || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // Низкочастотный фильтр: оцениваем гравитацию.
        gravity[0] = GRAVITY_FILTER_ALPHA * gravity[0] + (1 - GRAVITY_FILTER_ALPHA) * x;
        gravity[1] = GRAVITY_FILTER_ALPHA * gravity[1] + (1 - GRAVITY_FILTER_ALPHA) * y;
        gravity[2] = GRAVITY_FILTER_ALPHA * gravity[2] + (1 - GRAVITY_FILTER_ALPHA) * z;

        // Высокочастотная часть: резкие движения/вибрация от касания телефона.
        float linearX = x - gravity[0];
        float linearY = y - gravity[1];
        float linearZ = z - gravity[2];
        float motion = (float) Math.sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ);

        // Сглаживание, чтобы убрать одиночные шумовые всплески.
        smoothedMotion = 0.75f * smoothedMotion + 0.25f * motion;

        long now = System.currentTimeMillis();

        if (now - lastUiUpdateTime > 250) {
            tvStatus.setText(String.format(Locale.getDefault(),
                    "Статус: движение %.2f м/с², порог %.1f", smoothedMotion, threshold));
            lastUiUpdateTime = now;
        }

        if (smoothedMotion >= threshold && now - lastRepTime >= MIN_REP_INTERVAL_MS) {
            lastRepTime = now;
            pushUpCount++;
            saveState();
            updateUi();
            vibrateShort();

            if (pushUpCount >= goal && !goalNotificationShown) {
                goalNotificationShown = true;
                showGoalNotification();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Для акселерометра в этой лабораторной работе точность не критична.
    }

    private void updateUi() {
        tvCount.setText(String.valueOf(pushUpCount));
        tvGoal.setText(String.format(Locale.getDefault(), "Цель: %d отжиманий", goal));
        updateThresholdText();
    }

    private void updateThresholdText() {
        if (tvThreshold != null) {
            tvThreshold.setText(String.format(Locale.getDefault(),
                    "Чувствительность: %.1f м/с²", threshold));
        }
    }

    private void saveState() {
        preferences.edit()
                .putInt("goal", goal)
                .putInt("count", pushUpCount)
                .putFloat("threshold", threshold)
                .apply();
    }

    private void vibrateShort() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(70);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Цель отжиманий",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Уведомление при достижении цели тренировки");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void showGoalNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Цель достигнута!", Toast.LENGTH_LONG).show();
            return;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setSmallIcon(R.drawable.ic_fitness)
                .setContentTitle("Цель достигнута")
                .setContentText("Вы сделали " + pushUpCount + " отжиманий. Отличная работа!")
                .setAutoCancel(true)
                .build();

        manager.notify(NOTIFICATION_ID, notification);
    }
}
