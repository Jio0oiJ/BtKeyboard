package com.btkeyboard.hid;

/**
 * Ctrl / Alt / Win / Shift 的三态粘滞状态机（纯 Java，不依赖 Android，便于单测）。
 *
 * <p>每个修饰位（HID 报告第 0 字节的单个 bit）独立保存状态：
 * <ul>
 *   <li>{@link #OFF}：未按下，键帽为普通深色。</li>
 *   <li>{@link #ONESHOT}：点一下进入「一次性」，只作用于接下来按下的普通键，
 *       普通键全部抬起后自动清除（键帽蓝色）。</li>
 *   <li>{@link #LOCKED}：双击（400ms 内同键再点）进入「粘滞锁定」，持续保持，
 *       字体加粗；再点一下恢复 OFF。</li>
 * </ul>
 *
 * <p>单击是<strong>立即生效</strong>的，不等待双击超时，否则「点 Ctrl 马上按 C」会失效；
 * 第二次点击命中双击窗口时再把 ONESHOT 升级成 LOCKED。
 *
 * <p>一次性位从「点击的那一刻」起就写进 HID 报告（{@link #reportMods()}），也就是主机
 * 会先看到一个独立的 Ctrl keydown，再看到 Ctrl+空格，最后看到抬起。这一点很关键：
 * Windows 等主机的输入法热键需要单独识别到修饰键按下，如果 Ctrl 只和普通键出现在同一份报告里、
 * 又立刻消失，热键就不会触发（v1.6.0 的 bug）。一次性位在下一个普通键抬起后被消费掉。
 */
public final class ModStateMachine {

    public static final int OFF = 0;
    public static final int ONESHOT = 1;
    public static final int LOCKED = 2;

    /** 双击判定窗口；比系统 ViewConfiguration 的 300ms 略宽，手指点击更从容。 */
    public static final long DOUBLE_TAP_MS = 400L;

    private int once;       // 一次性修饰位掩码
    private int locked;     // 锁定粘滞修饰位掩码
    private int lastBit;    // 上一次点击的修饰位
    private long lastTime;  // 上一次点击时间（uptimeMillis）
    private boolean armed;  // 上一次点击产生的 ONESHOT 是否还能被双击升级

    /** 查询某个修饰位的当前状态。bit 必须是单个 HID 修饰位（2 的幂）。 */
    public synchronized int state(int bit) {
        if ((locked & bit) != 0) return LOCKED;
        if ((once & bit) != 0) return ONESHOT;
        return OFF;
    }

    /**
     * 点击一次修饰键，返回该位点击后的状态。
     *
     * @param bit   修饰位
     * @param nowMs 单调时钟（SystemClock.uptimeMillis()）
     */
    public synchronized int click(int bit, long nowMs) {
        long delta = nowMs - lastTime;
        boolean doubleTap = armed && lastBit == bit && delta >= 0 && delta <= DOUBLE_TAP_MS;
        int cur = state(bit);
        int next;

        if (cur == LOCKED) {
            // 锁定态再按一下恢复
            locked &= ~bit;
            once &= ~bit;
            armed = false;
            next = OFF;
        } else if (cur == ONESHOT) {
            once &= ~bit;
            if (doubleTap) {
                locked |= bit;
                next = LOCKED;
            } else {
                // 超过双击窗口再点同一键 = 取消一次性
                next = OFF;
            }
            armed = false;
        } else {
            // OFF → 一次性，立即生效，不等双击超时
            once |= bit;
            armed = true;
            next = ONESHOT;
        }

        lastBit = bit;
        lastTime = nowMs;
        return next;
    }

    /**
     * 普通键（或一键组合键）用完后调用：清掉所有一次性位，锁定位保持。
     * 同时取消双击跟踪，避免「点 Ctrl → 按了别的键 → 再点 Ctrl」被误判成双击。
     *
     * @return 是否真的清掉了一次性位（用于决定要不要刷新键帽）
     */
    public synchronized boolean consumeOneShot() {
        boolean changed = once != 0;
        once = 0;
        armed = false;
        lastBit = 0;
        return changed;
    }

    /**
     * HID 报告第 0 字节应写入的修饰位：锁定与一次性都从点击起持续置位，
     * 直到被 {@link #consumeOneShot()}、取消点击或 CLR 清掉。
     */
    public synchronized int reportMods() {
        return locked | once;
    }

    public synchronized int onceMask() {
        return once;
    }

    public synchronized int lockedMask() {
        return locked;
    }

    /** CLR：清空全部状态与双击跟踪。 */
    public synchronized void clear() {
        once = 0;
        locked = 0;
        lastBit = 0;
        lastTime = 0;
        armed = false;
    }
}
