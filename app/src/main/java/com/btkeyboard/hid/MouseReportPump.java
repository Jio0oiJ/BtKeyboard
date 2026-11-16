package com.btkeyboard.hid;

import java.util.ArrayDeque;

/**
 * 相对鼠标报告的「实时合并 + 过期截断」发送器（纯 Java，可脱离 Android 单测）。
 *
 * <p>问题背景：BluetoothHidDevice.sendReport 是跨进程调用，蓝牙链路从 sniff 省电态唤醒时
 * 首包可能耗时上百毫秒。旧实现把每条位移报告塞进无界线程池队列，首包堵塞期间积压的
 * 十几条报告会在链路唤醒后被连续回放，主机指针于是「先卡住、再跳一大段」。
 *
 * <p>相对位移是<b>实时量</b>，长时间积压的历史数据没有补发价值。本类的做法：
 * <ul>
 *   <li>UI 线程只把小位移累加进一个累加器，专用发送线程按 ~8ms 一帧取走并合并成一条报告；
 *       队列里永远最多只有一帧待发，不会回放积压；</li>
 *   <li>v1.7.2：{@link #STALE_MS} 放宽到 120ms。蓝牙连接间隔/调度抖动 40~80ms 属正常，
 *       v1.7.1 的 35ms 会把整段位移丢光，造成移动中「时不时停一下」；现在只有真正长时间
 *       阻塞才处理，且<b>不再整段丢弃</b>，只把补发量截断到 {@link #MAX_CATCHUP}
 *       （既不瞬移，也不会完全没反应）；</li>
 *   <li>单帧每轴软上限 {@link #SOFT_MAX_STEP}，大位移分帧发送，减少主机指针加速带来的突跳；</li>
 *   <li>按钮状态变化单独排队，保证 down / up 不被合并吞掉（否则单击会失效）；</li>
 *   <li>{@link #kick()} 发一条零位移报告用于预热/保活蓝牙中断通道；
 *       {@link #lastSendAt()} 让调用方只在真正空闲时保活，移动中不插额外报告；</li>
 *   <li>实际 sendReport 在锁外执行，避免阻塞 UI 线程的触摸采样。</li>
 * </ul>
 */
public final class MouseReportPump {

    public interface Sink {
        /** 在 pump 的发送线程上调用；实现里直接 sendReport，不要再切线程或排队。 */
        void send(int buttons, int dx, int dy, int wheel, int pan);
    }

    public interface Clock {
        long nowMs();
    }

    /**
     * 目标发送周期，约 90Hz。v1.7.1/1.7.2 用 8ms(125Hz)，超过蓝牙 HID 中断通道
     * 常见连接间隔的承载能力，协议栈内部队列会积压并偶发流控停顿；降到 11ms 后
     * 每帧合并更多位移但总位移不变，链路更平稳。
     */
    public static final int FRAME_MS = 11;
    /**
     * 累加器里的位移超过这个时长仍未发出才算「过期」。
     * 蓝牙 HID 中断通道的连接间隔抖动常见 40~80ms，阈值必须明显大于它，
     * 否则正常移动会被误判为过期并丢位移（v1.7.1 的 35ms 就会）。
     */
    public static final int STALE_MS = 120;
    /** 单帧每轴软上限：大位移分多帧发，避免一条报告里塞 ±127 造成突跳。 */
    public static final int SOFT_MAX_STEP = 64;
    /** 长阻塞后最多补发多少位移（约两帧），超出的历史位移截断丢弃。 */
    public static final int MAX_CATCHUP = SOFT_MAX_STEP * 2;
    /** 超过这么久没发过报告才认为链路空闲，需要保活。 */
    public static final long IDLE_MS = 200;
    private static final int MAX_BUTTON_EVENTS = 12;

    public static final class Frame {
        public final int buttons;
        public final int dx, dy, wheel, pan;
        /** 本帧是否截断了过期位移（用于诊断/测试）。 */
        public final boolean dropped;

        Frame(int buttons, int dx, int dy, int wheel, int pan, boolean dropped) {
            this.buttons = buttons;
            this.dx = dx;
            this.dy = dy;
            this.wheel = wheel;
            this.pan = pan;
            this.dropped = dropped;
        }

        public boolean isIdle() {
            return buttons == 0 && dx == 0 && dy == 0 && wheel == 0 && pan == 0;
        }

        @Override
        public String toString() {
            return "Frame(btn=" + buttons + ", dx=" + dx + ", dy=" + dy
                    + ", wheel=" + wheel + ", pan=" + pan + ", dropped=" + dropped + ")";
        }
    }

    private final Sink sink;
    private final Clock clock;
    private final Runnable workerHook;
    private final Object lock = new Object();
    private final ArrayDeque<Integer> buttonEvents = new ArrayDeque<>();
    /** 只在 lock 内使用的临时数组，避免每帧分配对象造成 GC 抖动。 */
    private final long[] accScratch = new long[4];
    private final int[] outScratch = new int[4];

    private long accX, accY, accWheel, accPan;
    /** 已发出报告里的按钮状态。 */
    private int buttons;
    /** 最近一次 submit 传进来的按钮状态，用于检测变化。 */
    private int desiredButtons;
    /** 累加器中最早一个位移采样的时刻；-1 表示累加器为空。 */
    private long firstSampleAt = -1;
    private boolean kick;
    private long droppedSamples;
    private long sentFrames;
    private volatile long lastSendAt;

    private final Thread worker;
    private volatile boolean running;

    public MouseReportPump(Sink sink, Clock clock, Runnable workerHook) {
        this.sink = sink;
        this.clock = clock;
        this.workerHook = workerHook;
        this.worker = new Thread(this::loop, "bt-hid-mouse");
        this.worker.setPriority(Thread.MAX_PRIORITY);
        this.worker.setDaemon(true);
    }

    public void start() {
        if (running) return;
        running = true;
        worker.start();
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }

    /** UI 线程调用：只做一次轻量加锁累加，绝不等待蓝牙。 */
    public void submit(int buttons, int dx, int dy, int wheel, int pan) {
        long now = clock.nowMs();
        synchronized (lock) {
            int b = buttons & 0x07;
            if (b != desiredButtons) {
                buttonEvents.addLast(b);
                while (buttonEvents.size() > MAX_BUTTON_EVENTS) buttonEvents.removeFirst();
                desiredButtons = b;
            }
            if ((dx | dy | wheel | pan) != 0) {
                if (firstSampleAt < 0) firstSampleAt = now;
                accX += dx;
                accY += dy;
                accWheel += wheel;
                accPan += pan;
            }
        }
    }

    /** 强制下一帧发一条报告（哪怕位移为 0）：手指按下前预热 / 空闲时保活链路。 */
    public void kick() {
        synchronized (lock) {
            kick = true;
        }
    }

    /** 清空所有待发位移与按钮队列，并强制发一条「全部松开」的报告。 */
    public void reset() {
        synchronized (lock) {
            accX = accY = accWheel = accPan = 0;
            firstSampleAt = -1;
            buttonEvents.clear();
            buttons = 0;
            desiredButtons = 0;
            kick = true;
        }
    }

    /**
     * 取一帧并在锁外发送。抽成 public 便于纯 Java 单测用假时钟驱动（不启动线程）。
     *
     * @return 本帧内容；null 表示这一帧无事可做
     */
    public Frame drainOnce(long nowMs) {
        Frame f;
        final int cap = Math.min(SOFT_MAX_STEP, 127);
        synchronized (lock) {
            boolean buttonChanged = !buttonEvents.isEmpty();
            if (buttonChanged) buttons = buttonEvents.removeFirst();

            accScratch[0] = accX;
            accScratch[1] = accY;
            accScratch[2] = accWheel;
            accScratch[3] = accPan;

            boolean stale = firstSampleAt >= 0 && (nowMs - firstSampleAt) > STALE_MS;
            boolean dropped = false;
            if (stale) {
                // 只把补发量截断到上限，不整段丢光；正常蓝牙抖动根本不会走到这里
                for (int i = 0; i < 4; i++) {
                    if (accScratch[i] > MAX_CATCHUP) {
                        accScratch[i] = MAX_CATCHUP;
                        dropped = true;
                    } else if (accScratch[i] < -MAX_CATCHUP) {
                        accScratch[i] = -MAX_CATCHUP;
                        dropped = true;
                    }
                }
                if (dropped) droppedSamples++;
                // 刷新时间戳：保留下来的补发量要在后续几帧连续发完，不能被反复判为过期
                firstSampleAt = hasAccumulator(accScratch) ? nowMs : -1;
            }

            for (int i = 0; i < 4; i++) {
                long v = Math.max(-1000000L, Math.min(1000000L, accScratch[i]));
                outScratch[i] = (int) Math.max(-cap, Math.min(cap, v));
                accScratch[i] -= outScratch[i];   // 超出软上限的部分留到下一帧
            }
            accX = accScratch[0];
            accY = accScratch[1];
            accWheel = accScratch[2];
            accPan = accScratch[3];
            if (!hasAccumulator(accScratch)) firstSampleAt = -1;

            boolean hasData = (outScratch[0] | outScratch[1] | outScratch[2] | outScratch[3]) != 0;
            boolean force = kick;
            kick = false;
            if (!hasData && !buttonChanged && !force) return null;
            sentFrames++;
            f = new Frame(buttons, outScratch[0], outScratch[1],
                    outScratch[2], outScratch[3], dropped);
        }
        // 必须在锁外：sendReport 可能阻塞，不能让 UI 线程的 submit 一起卡住
        sink.send(f.buttons, f.dx, f.dy, f.wheel, f.pan);
        lastSendAt = clock.nowMs();
        return f;
    }

    private static boolean hasAccumulator(long[] a) {
        return a[0] != 0 || a[1] != 0 || a[2] != 0 || a[3] != 0;
    }

    private void loop() {
        if (workerHook != null) {
            try {
                workerHook.run();
            } catch (Throwable ignored) {
            }
        }
        while (running) {
            long t0 = clock.nowMs();
            try {
                drainOnce(t0);
            } catch (Throwable ignored) {
            }
            long nap = FRAME_MS - (clock.nowMs() - t0);
            if (nap > 0) {
                try {
                    Thread.sleep(nap);
                } catch (InterruptedException e) {
                    if (!running) break;
                }
            }
        }
    }

    /** 最近一次真正发出报告的时刻，用于判断链路是否空闲。 */
    public long lastSendAt() {
        return lastSendAt;
    }

    /** 累计发生「过期截断」的次数（正常移动应为 0）。 */
    public long droppedSamples() {
        synchronized (lock) {
            return droppedSamples;
        }
    }

    public long sentFrames() {
        synchronized (lock) {
            return sentFrames;
        }
    }
}
