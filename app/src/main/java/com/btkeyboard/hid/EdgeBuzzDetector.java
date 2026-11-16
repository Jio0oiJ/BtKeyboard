package com.btkeyboard.hid;

/**
 * 触控板「手指移到边缘震一下」的纯 Java 判定器（可脱离 Android 单测）。
 *
 * <p>采用<b>边沿触发</b>：只有从非边缘区进入边缘区才考虑震动，沿边缘滑动不会连续震；
 * 再加冷却时间，避免手指在边界附近来回抖动时频繁打扰。按在边缘上开始手势不震动，
 * 必须先回到内部再出去。
 */
public final class EdgeBuzzDetector {

    /** 两次边缘提示之间的最小间隔。 */
    public static final long COOLDOWN_MS = 300;

    private boolean enabled = true;
    private boolean wasAtEdge;
    private long lastBuzzAt = -COOLDOWN_MS;

    /** 判断某点是否落在距边界 slop 以内的边缘带。视图尚未测量时返回 false。 */
    public static boolean isAtEdge(float x, float y, float w, float h, float slop) {
        if (w <= 0 || h <= 0 || slop <= 0) return false;
        return x < slop || y < slop || x > w - slop || y > h - slop;
    }

    /** 新手势开始：startAtEdge 表示手指一开始就按在边缘（此时不应震动）。 */
    public void reset(boolean startAtEdge) {
        wasAtEdge = startAtEdge;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) wasAtEdge = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 喂入一个采样点。
     *
     * @param atEdge 该点是否在边缘带
     * @param nowMs  单调递增时间（uptimeMillis）
     * @return 本次是否需要震动一下
     */
    public boolean update(boolean atEdge, long nowMs) {
        boolean fire = enabled && atEdge && !wasAtEdge
                && (nowMs - lastBuzzAt >= COOLDOWN_MS);
        if (fire) lastBuzzAt = nowMs;
        wasAtEdge = atEdge;
        return fire;
    }
}
