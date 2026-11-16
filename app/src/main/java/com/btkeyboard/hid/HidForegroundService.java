package com.btkeyboard.hid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 保活 HID 注册的前台服务。
 *
 * <p>AOSP HidDeviceService（Android 9 至今）注册了 OnUidImportanceListener，
 * 一旦 App 进程 importance 超过 IMPORTANCE_VISIBLE(200) 就调用 unregisterAppUid()，
 * 也就是「切到系统设置去配对」「锁屏」「切到别的 App」都会让 HID SDP 记录被撤销，
 * 主机端就只能看到一台普通手机。前台服务把进程 importance 保持在
 * IMPORTANCE_FOREGROUND_SERVICE(125)，从而让注册持续有效。
 */
public final class HidForegroundService extends Service {

    private static final String TAG = "BtKeyboardHID";
    private static final String CHANNEL = "btkeyboard_hid";
    private static final int NOTIFICATION_ID = 20261116;
    private static final String ACTION_STOP = "com.btkeyboard.hid.STOP";

    private static volatile boolean running;
    private static volatile boolean foregroundOk;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isForegroundOk() {
        return foregroundOk;
    }

    public static void start(Context context) {
        try {
            Intent i = new Intent(context, HidForegroundService.class);
            context.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed: " + t);
        }
    }

    public static void stop(Context context) {
        try {
            Intent i = new Intent(context, HidForegroundService.class);
            i.setAction(ACTION_STOP);
            context.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "stop service failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        running = true;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            foregroundOk = true;
        } catch (Throwable t) {
            foregroundOk = false;
            Log.e(TAG, "startForeground failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            HidKeyboard.get(this).shutdown();
            running = false;
            foregroundOk = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 系统重建服务时也要重新注册 HID
        HidKeyboard.get(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        foregroundOk = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL,
                "蓝牙键盘服务", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("保持蓝牙 HID 键盘注册，切到后台也不断线");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, HidForegroundService.class).setAction(ACTION_STOP), flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("蓝牙键盘运行中")
                .setContentText("保持此通知，切到后台或锁屏不会断开 HID 键盘")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher),
                        "停止并注销", stop).build())
                .build();
    }
}
