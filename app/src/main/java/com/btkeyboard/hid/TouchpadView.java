package com.btkeyboard.hid;

import android.content.Context;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 模拟相对鼠标的触控板手势视图（纯框架 API，无第三方依赖）。
 *
 * <p>手势：
 * <ul>
 *   <li>单指移动 → 相对位移（X/Y）；</li>
 *   <li>轻点（位移小于阈值、时长短）→ 左键单击；</li>
 *   <li>长按后拖动 → 按住左键拖拽；</li>
 *   <li>双指上下/左右移动 → 纵向滚轮 / 横向 AC Pan；</li>
 *   <li>手指移到触控板边缘 → 震动一下提示（边沿触发 + 冷却，可在控制条关闭）；</li>
 * </ul>
 *
 * <p>位移采样交给 {@link MouseReportPump} 统一合并与限速：这里只保留不足 1 像素的浮点
 * 余量，不再自己做节流和队列。ACTION_MOVE 会遍历历史采样点，逐点累加后<b>整个事件只提交
 * 一次</b>，减少 UI 线程与发送线程之间的锁竞争；边缘判定只看当前点，避免历史点抖动误触发。
 */
public final class TouchpadView extends View {

    public interface Sink {
        void mouse(int buttons, int dx, int dy, int wheel, int pan);
        /** 预热蓝牙中断通道：强制发一条零位移报告。 */
        void warm();
        /** 清空待发位移并释放所有鼠标按键。 */
        void resetMouse();
        /** 普通点击反馈。 */
        void buzz();
        /** 手指进入边缘带的触觉提示。 */
        void edgeBuzz();
    }

    private static final long TAP_MS = 220;
    private static final long LONG_PRESS_MS = 240;
    private static final long CLICK_HOLD_MS = 55;
    private static final float SCROLL_SCALE = 0.42f;
    private static final float EDGE_SLOP_DIP = 16f;
    private static final float[] SENSITIVITIES = {1.0f, 1.6f, 2.4f, 3.4f};

    private final float tapSlop;
    private final float edgeSlop;
    private final EdgeBuzzDetector edgeBuzz = new EdgeBuzzDetector();
    private Sink sink;

    private int sensitivityIndex = 1;
    private boolean dragLock;
    private boolean dragging;
    private boolean scrolling;
    private boolean longPressCancelled;
    private int activePointerId = -1;
    private int buttonMask;

    private float lastX, lastY, travel;
    private float scrollLastX, scrollLastY;
    private long downAt;

    /** 不足 1 个 HID 计数单位的浮点余量，跨事件保留，避免慢速移动被取整抹平。 */
    private float accX, accY, accWheel, accPan;
    private Runnable pendingClickRelease;

    private final Runnable longPress = () -> {
        if (scrolling || activePointerId < 0) return;
        dragging = true;
        // 立刻把「左键按下」的状态发给主机，别等下一次位移报告
        send(stateButtons(), 0, 0, 0, 0);
        if (sink != null) sink.buzz();
    };

    public TouchpadView(Context context) {
        super(context);
        tapSlop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 11f,
                getResources().getDisplayMetrics());
        edgeSlop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, EDGE_SLOP_DIP,
                getResources().getDisplayMetrics());
        setClickable(true);
    }

    public void setSink(Sink s) {
        this.sink = s;
    }

    // ---------------------------------------------------------------- 对外控制

    public String cycleSensitivity() {
        sensitivityIndex = (sensitivityIndex + 1) % SENSITIVITIES.length;
        return sensitivityLabel();
    }

    public String sensitivityLabel() {
        return String.format(java.util.Locale.getDefault(), "%.1f×", SENSITIVITIES[sensitivityIndex]);
    }

    private float sensitivity() {
        return SENSITIVITIES[sensitivityIndex];
    }

    public boolean isDragLock() {
        return dragLock;
    }

    public boolean toggleDragLock() {
        dragLock = !dragLock;
        send(stateButtons(), 0, 0, 0, 0);
        return dragLock;
    }

    /** 控制条上的左/中/右键：down 置位、up 清位。 */
    public void setButton(int bit, boolean down) {
        if (down) buttonMask |= bit;
        else buttonMask &= ~bit;
        send(stateButtons(), 0, 0, 0, 0);
    }

    public boolean isEdgeBuzzEnabled() {
        return edgeBuzz.isEnabled();
    }

    public void setEdgeBuzzEnabled(boolean on) {
        edgeBuzz.setEnabled(on);
    }

    public void doubleClick() {
        cancelClickRelease();
        click();
        postDelayed(this::click, 140);
    }

    /** 离开页签 / 切后台时调用，释放所有按住的鼠标键并丢弃未发完的位移。 */
    public void releaseAll() {
        removeCallbacks(longPress);
        cancelClickRelease();
        buttonMask = 0;
        dragging = false;
        scrolling = false;
        dragLock = false;
        longPressCancelled = false;
        edgeBuzz.reset(false);
        activePointerId = -1;
        resetAccumulator();
        if (sink != null) sink.resetMouse();
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cancelClickRelease();
                removeCallbacks(longPress);
                activePointerId = ev.getPointerId(0);
                lastX = ev.getX(0);
                lastY = ev.getY(0);
                downAt = SystemClock.uptimeMillis();
                travel = 0;
                dragging = false;
                scrolling = false;
                longPressCancelled = false;
                resetAccumulator();
                // 一开始就按在边缘上不算「移到边缘」，不震动
                edgeBuzz.reset(isAtEdge(lastX, lastY));
                // 手指落下前先唤醒链路，首条位移报告就不会卡在蓝牙 sniff 唤醒上
                if (sink != null) sink.warm();
                postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    removeCallbacks(longPress);
                    flushAccumulator();
                    dragging = false;
                    scrolling = true;
                    edgeBuzz.reset(false);
                    float[] mid = midpoint(ev);
                    scrollLastX = mid[0];
                    scrollLastY = mid[1];
                    send(stateButtons(), 0, 0, 0, 0);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scrolling && ev.getPointerCount() >= 2) {
                    float[] mid = midpoint(ev);
                    float dx = (mid[0] - scrollLastX) * SCROLL_SCALE;
                    float dy = (mid[1] - scrollLastY) * SCROLL_SCALE;
                    scrollLastX = mid[0];
                    scrollLastY = mid[1];
                    // 手指向下移 → 内容向下滚（wheel 取负更符合多数系统的自然方向）
                    accWheel += -dy;
                    accPan += dx;
                    emit();
                } else if (activePointerId >= 0) {
                    int idx = ev.findPointerIndex(activePointerId);
                    if (idx >= 0) {
                        // 系统会把多个采样点合并进一个 MOVE；逐点累加，整个事件只提交一次
                        int history = ev.getHistorySize();
                        for (int i = 0; i < history; i++) {
                            step(ev.getHistoricalX(idx, i), ev.getHistoricalY(idx, i));
                        }
                        float cx = ev.getX(idx);
                        float cy = ev.getY(idx);
                        step(cx, cy);
                        emit();
                        // 边缘只看当前点，避免历史点在边界内外抖动造成重复提示
                        checkEdge(cx, cy);
                    }
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (scrolling && ev.getPointerCount() <= 2) {
                    flushAccumulator();
                    scrolling = false;
                    int remaining = ev.getActionIndex() == 0 ? 1 : 0;
                    if (remaining < ev.getPointerCount()) {
                        activePointerId = ev.getPointerId(remaining);
                        lastX = ev.getX(remaining);
                        lastY = ev.getY(remaining);
                        edgeBuzz.reset(isAtEdge(lastX, lastY));
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                flushAccumulator();
                boolean tap = !scrolling && !dragging && travel < tapSlop
                        && SystemClock.uptimeMillis() - downAt < TAP_MS;
                scrolling = false;
                dragging = false;
                edgeBuzz.reset(false);
                activePointerId = -1;
                if (tap && !dragLock && action != MotionEvent.ACTION_CANCEL) {
                    click();
                } else {
                    // 拖拽结束时把按键抬起；相对位移是瞬时的，不需要额外清零报告
                    send(stateButtons(), 0, 0, 0, 0);
                }
                performClick();
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 单个采样点：只累加浮点位移，整个 ACTION_MOVE 结束时统一 emit()。 */
    private void step(float x, float y) {
        float dx = (x - lastX) * sensitivity();
        float dy = (y - lastY) * sensitivity();
        lastX = x;
        lastY = y;
        accX += dx;
        accY += dy;
        travel += Math.abs(dx) + Math.abs(dy);
        if (!longPressCancelled && travel > tapSlop) {
            longPressCancelled = true;
            removeCallbacks(longPress);
        }
    }

    private boolean isAtEdge(float x, float y) {
        return EdgeBuzzDetector.isAtEdge(x, y, getWidth(), getHeight(), edgeSlop);
    }

    private void checkEdge(float x, float y) {
        if (edgeBuzz.update(isAtEdge(x, y), SystemClock.uptimeMillis())) {
            Sink s = sink;
            if (s != null) s.edgeBuzz();
        }
    }

    private float[] midpoint(MotionEvent ev) {
        float x = 0, y = 0;
        int n = Math.min(ev.getPointerCount(), 2);
        for (int i = 0; i < n; i++) {
            x += ev.getX(i);
            y += ev.getY(i);
        }
        return new float[]{x / n, y / n};
    }

    // ---------------------------------------------------------------- 报告合成

    private int stateButtons() {
        return buttonMask | ((dragging || dragLock) ? HidKeyMap.BTN_LEFT : 0);
    }

    private void click() {
        buttonMask |= HidKeyMap.BTN_LEFT;
        send(stateButtons(), 0, 0, 0, 0);
        if (sink != null) sink.buzz();
        cancelClickRelease();
        pendingClickRelease = () -> {
            buttonMask &= ~HidKeyMap.BTN_LEFT;
            send(stateButtons(), 0, 0, 0, 0);
            pendingClickRelease = null;
        };
        postDelayed(pendingClickRelease, CLICK_HOLD_MS);
    }

    private void cancelClickRelease() {
        if (pendingClickRelease != null) {
            removeCallbacks(pendingClickRelease);
            pendingClickRelease = null;
        }
    }

    private void resetAccumulator() {
        accX = accY = accWheel = accPan = 0;
    }

    /** 把一个 ACTION_MOVE 的整数部分一次性提交给合并泵，浮点余量留到下一次。 */
    private void emit() {
        int ix = Math.round(accX);
        int iy = Math.round(accY);
        int iw = Math.round(accWheel);
        int ip = Math.round(accPan);
        if ((ix | iy | iw | ip) == 0) return;
        accX -= ix;
        accY -= iy;
        accWheel -= iw;
        accPan -= ip;
        send(stateButtons(), ix, iy, iw, ip);
    }

    /** 抬手 / 切滚动模式时提交剩余整数部分并丢弃不足 1 的余量（不补发历史位移）。 */
    private void flushAccumulator() {
        emit();
        resetAccumulator();
    }

    private void send(int buttons, int dx, int dy, int wheel, int pan) {
        Sink s = sink;
        if (s != null) s.mouse(buttons, dx, dy, wheel, pan);
    }
}
