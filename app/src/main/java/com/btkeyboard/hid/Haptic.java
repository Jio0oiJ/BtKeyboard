package com.btkeyboard.hid;

import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 触觉反馈：{@link Vibrator#vibrate(long)} 是同步跨进程调用，放在 UI 线程会卡住触摸采样，
 * 边缘提示在移动过程中触发时尤其明显。这里统一放到后台单线程执行，并做最小间隔节流，
 * 避免点击反馈与边缘提示叠加造成振动堆积。
 */
public final class Haptic {

    private static final long MIN_INTERVAL_MS = 80;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bt-hid-buzz");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastAt;

    private Haptic() {}

    /** 普通点击反馈：短促 10ms。 */
    public static void buzz(Vibrator v) {
        buzz(v, 10);
    }

    /** 边缘提示：稍长一点的 22ms，和点击区分开。 */
    public static void edgeBuzz(Vibrator v) {
        buzz(v, 22);
    }

    private static void buzz(final Vibrator v, final int ms) {
        if (v == null) return;
        final long now = SystemClock.uptimeMillis();
        if (now - lastAt < MIN_INTERVAL_MS) return;
        lastAt = now;
        IO.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(
                            ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
