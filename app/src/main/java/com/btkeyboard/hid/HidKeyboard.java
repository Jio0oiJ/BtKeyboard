package com.btkeyboard.hid;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把本机注册成蓝牙 HID「键盘 + 触控板」复合设备（Android 9 / API 28+ 的 BluetoothHidDevice）。
 *
 * <p>关键契约（已对 AOSP / Bluedroid 源码核实）：
 * <br>1. registerApp(sdp, inQos, outQos, executor, callback) —— QoS 允许为 null；
 * <br>2. sendReport(device, reportId, data) —— 第二参数是 <b>Report Id</b> 而非 Report Type，
 * data <b>不含</b> id 前缀。Bluedroid 的 bta_hd_register_act 扫描描述符是否含 0x85 项，
 * 有则置 use_report_id，协议栈在 HID 中断 payload 前自动补上这个 id 字节
 * （hidd_conn_send_data: 仅在 id 非零时拼接）。复合模式下键盘报告传 1、鼠标报告传 2；
 * 「仅键盘」兼容模式或 boot 协议下传 0；
 * <br>3. connect()/registerApp() 的返回值只代表「命令已送出」，<b>不代表连接/注册成功</b>，
 * 必须靠回调；但部分 ROM 回调会丢失，所以这里用 getConnectionState / getConnectedDevices
 * 主动轮询兜底，避免界面永远停在「正在连接…」；
 * <br>4. HidDeviceService 会在 App 退到后台时自动注销注册（uid importance &gt;
 * IMPORTANCE_VISIBLE），必须由 HidForegroundService 保活。
 *
 * <p>⚠ 描述符变更意味着主机缓存的旧 SDP 记录失效，切换模式后两端都要「忘记设备」重新配对。
 */
public final class HidKeyboard {

    private static final String TAG = "BtKeyboardHID";
    public static final int ST_ERROR = 0;
    public static final int ST_WAIT = 1;
    public static final int ST_OK = 2;

    public interface Listener {
        void onState(int level, String title, String detail);
        void onCapsLed(boolean on);
        void onBluetoothDisabled();
    }

    private static volatile HidKeyboard instance;

    public static HidKeyboard get(Context context) {
        if (instance == null) {
            synchronized (HidKeyboard.class) {
                if (instance == null) instance = new HidKeyboard(context.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bt-hid-io");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final ArrayDeque<String> events = new ArrayDeque<>();

    /** 鼠标报告泵：位移实时合并、过期截断，绝不排队补发（避免蓝牙唤醒后指针瞬移）。 */
    private final MouseReportPump mousePump;
    /** 只在链路真正空闲时发零位移报告，避免蓝牙进入 sniff 造成首包延迟。 */
    private static final long MOUSE_KEEPALIVE_MS = 400;
    private volatile boolean mouseKeepAlive;
    /** sendReport 偶发返回 false 只是蓝牙流控，连续失败才需要异步兜底重同步。 */
    private volatile int mouseSendFailures;
    private volatile long lastMouseSendOkAt = SystemClock.uptimeMillis();
    private volatile long lastKeyboardResyncAt;
    private final Runnable mouseKeepAliveTask = new Runnable() {
        @Override
        public void run() {
            if (!mouseKeepAlive) return;
            long now = SystemClock.uptimeMillis();
            // 移动过程中每帧都有真实报告，不要插入 idle 帧多占一次跨进程调用
            if (composite && appRegistered && host != null
                    && now - mousePump.lastSendAt() > MouseReportPump.IDLE_MS) {
                mousePump.kick();
            }
            main.postDelayed(this, MOUSE_KEEPALIVE_MS);
        }
    };

    private BluetoothAdapter adapter;
    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private volatile BluetoothDevice lastHost;
    private volatile boolean appRegistered;
    private volatile boolean proxyRequested;
    private volatile boolean manualDisconnect;
    /** 切换设备时延迟执行的连接任务，避免快速连点造成多个连接请求打架。 */
    private volatile Runnable pendingSwitchConnect;
    private volatile boolean receiverRegistered;
    private volatile boolean shuttingDown;
    private volatile String registerReturn = "未调用";
    private volatile int lastConnectionState = BluetoothProfile.STATE_DISCONNECTED;
    private volatile long proxyRequestedAt;
    private volatile byte[] lastReport = new byte[HidKeyMap.REPORT_LEN_KEYBOARD];
    /** 鼠标只缓存按钮状态：相对位移是瞬时事件，GET_REPORT 回复时必须全 0。 */
    private volatile int lastMouseButtons;
    /** true = 复合描述符（键盘 + 触控板，Report ID 1/2）；false = 仅键盘，reportId 固定 0。 */
    private volatile boolean composite = true;
    /** 主机通过 SET_PROTOCOL 切到 boot 协议时，报告不带 Report ID。 */
    private volatile boolean bootMode;
    private int reconnectTries;
    private Listener listener;

    private HidKeyboard(Context context) {
        this.ctx = context;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        // 鼠标报告走独立的高优先级发送线程，键盘报告仍走 io，两者互不阻塞
        this.mousePump = new MouseReportPump(
                this::writeMouseReport,
                SystemClock::uptimeMillis,
                () -> {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    } catch (Throwable ignored) {
                    }
                });
        mousePump.start();
    }

    public void setListener(Listener l) {
        this.listener = l;
        if (l != null) publishState();
    }

    public boolean isConnected() {
        return host != null;
    }

    public boolean isRegistered() {
        return appRegistered;
    }

    /** 申请/初始化 HID 服务。权限与蓝牙开关由 Activity 处理，这里只做校验与提示。 */
    public synchronized void start() {
        shuttingDown = false;
        ensureReceiver();
        if (adapter == null) {
            state(ST_ERROR, "蓝牙不可用", "此设备没有蓝牙适配器，无法模拟蓝牙键盘");
            return;
        }
        if (!hasConnectPermission()) {
            state(ST_ERROR, "缺少蓝牙权限", "请授予「附近的设备」权限后点「重试」");
            return;
        }
        boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch (SecurityException e) {
            state(ST_ERROR, "缺少蓝牙权限", "请授予「附近的设备」权限后点「重试」");
            return;
        }
        if (!enabled) {
            state(ST_WAIT, "蓝牙未开启", "点「重试」开启蓝牙，或到系统设置里打开");
            if (listener != null) listener.onBluetoothDisabled();
            return;
        }
        if (hid != null) {
            if (!appRegistered) registerApp();
            else syncConnectedDevices(true);
            return;
        }
        if (proxyRequested) {
            if (System.currentTimeMillis() - proxyRequestedAt > 6000) {
                // 少数 ROM 的 getProfileProxy 永远不回调，超时后允许重试
                proxyRequested = false;
                log("HID 代理连接超时，重置后重试");
            } else {
                state(ST_WAIT, "正在初始化", "等待系统 HID Device 服务连接…");
                return;
            }
        }
        state(ST_WAIT, "正在初始化", "注册蓝牙 HID 键盘设备…");
        proxyRequested = true;
        proxyRequestedAt = System.currentTimeMillis();
        try {
            boolean ok = adapter.getProfileProxy(ctx, proxyListener, BluetoothProfile.HID_DEVICE);
            log("getProfileProxy(HID_DEVICE=19) -> " + ok);
            if (!ok) {
                proxyRequested = false;
                state(ST_ERROR, "无法连接 HID 服务",
                        "关闭再打开蓝牙后重试；或该 ROM 未启用 HID Device profile");
            }
        } catch (Throwable t) {
            proxyRequested = false;
            state(ST_ERROR, "蓝牙访问失败", String.valueOf(t.getMessage()));
        }
    }

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            proxyRequested = false;
            log("HID Device 代理已连接");
            registerApp();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            log("HID Device 代理断开");
            hid = null;
            host = null;
            appRegistered = false;
            proxyRequested = false;
            state(ST_ERROR, "HID 服务已断开", "关闭再打开蓝牙，然后点「重试」");
        }
    };

    private void registerApp() {
        final BluetoothHidDevice h = hid;
        if (h == null) return;
        try {
            boolean useCombo = composite;
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    useCombo ? "BT Keyboard + Touchpad" : "BT Keyboard",
                    useCombo ? "Bluetooth HID Keyboard and Touchpad"
                            : "Standard 26-key Bluetooth HID Keyboard",
                    "BtKeyboard",
                    useCombo ? BluetoothHidDevice.SUBCLASS1_COMBO
                            : BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    useCombo ? HidKeyMap.REPORT_DESCRIPTOR
                            : HidKeyMap.KEYBOARD_ONLY_DESCRIPTOR);
            boolean ok = h.registerApp(sdp, null, null, io, hidCallback);
            registerReturn = String.valueOf(ok);
            log("registerApp() -> " + ok + " / 模式=" + (useCombo ? "键盘+触控板" : "仅键盘")
                    + " / 描述符 " + (useCombo ? HidKeyMap.REPORT_DESCRIPTOR.length
                    : HidKeyMap.KEYBOARD_ONLY_DESCRIPTOR.length) + " 字节");
            // 返回 false 也可能是「另一个 App 占用 / 进程不在前台」；仍等回调确认
            main.postDelayed(() -> {
                if (!appRegistered && !shuttingDown) {
                    state(ST_ERROR, "HID 注册被拒绝",
                            "确认本 App 在前台、前台服务已运行，且没有其它 HID 类 App 占用");
                }
            }, 2500);
            main.postDelayed(() -> syncConnectedDevices(false), 900);
        } catch (Throwable t) {
            Log.e(TAG, "registerApp failed", t);
            log("registerApp 异常: " + t);
            state(ST_ERROR, "HID 注册异常", String.valueOf(t.getMessage()));
        }
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            appRegistered = registered;
            log("onAppStatusChanged registered=" + registered
                    + " plugged=" + (pluggedDevice == null ? "null" : pluggedDevice.getAddress()));
            if (!registered) {
                host = null;
                state(ST_ERROR, "HID 注册已失效",
                        "App 退到后台会被系统自动注销；点「重试」重新注册，并保持前台服务运行");
                return;
            }
            if (pluggedDevice != null) {
                host = pluggedDevice;
                lastHost = pluggedDevice;
                lastConnectionState = BluetoothProfile.STATE_CONNECTED;
                state(ST_OK, "已连接", nameOf(pluggedDevice) + " · 可以打字了");
                main.postDelayed(() -> send(new byte[8]), 250);
            } else {
                state(ST_WAIT, "已注册 · 等待主机连接",
                        "在主机上删除旧配对后重新搜索本机；配对期间手机保持本 App 前台");
            }
            syncConnectedDevices(false);
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int newState) {
            lastConnectionState = newState;
            log("onConnectionStateChanged " + nameOf(device) + " -> " + stateName(newState));
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                lastHost = device;
                manualDisconnect = false;
                reconnectTries = 0;
                state(ST_OK, "已连接", nameOf(device) + " · 可以打字了");
                // 连接建立后先发一条空报告，清掉可能残留的按键状态
                main.postDelayed(() -> send(new byte[8]), 250);
            } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                state(ST_WAIT, "正在连接…", nameOf(device) + " · 最多等待 10 秒");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                state(ST_WAIT, "正在断开…", nameOf(device));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.equals(device)) host = null;
                if (manualDisconnect) {
                    state(ST_WAIT, "已断开", "点「设备」重新连接主机");
                } else {
                    state(ST_WAIT, "连接已断开", "正在自动重连…");
                    scheduleReconnect(device);
                }
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            // 主机按 Report ID 分别索取；replyReport 的 data 不含 id 前缀
            byte[] r;
            if (composite && id == HidKeyMap.REPORT_ID_MOUSE) {
                // 相对位移是一次性事件，不是可持续读取的「状态」。把上一帧带 dx/dy 的
                // 报告原样回给主机，主机就会把那段位移再走一遍 —— 表现为移动中
                // 「偶尔顿一下 / 跳一下」。这里只回当前按钮状态，位移与滚轮全部为 0。
                r = new byte[bootMode ? HidKeyMap.REPORT_LEN_MOUSE_BOOT
                        : HidKeyMap.REPORT_LEN_MOUSE];
                r[0] = (byte) lastMouseButtons;
            } else {
                r = lastReport.clone();
            }
            if (r.length < bufferSize) r = Arrays.copyOf(r, bufferSize);
            BluetoothHidDevice h = hid;
            if (h != null) {
                try {
                    h.replyReport(device, type, id, r);
                } catch (Throwable t) {
                    Log.w(TAG, "replyReport failed", t);
                }
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            handleLeds(data);
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            handleLeds(data);
        }

        @Override
        public void onSetProtocol(BluetoothDevice device, byte protocol) {
            bootMode = protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE;
            log("onSetProtocol " + (bootMode ? "BOOT（报告不带 Report ID）" : "REPORT"));
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            log("onVirtualCableUnplug " + nameOf(device));
            host = null;
            state(ST_WAIT, "主机已断开", "点「设备」重新连接，或到主机蓝牙设置里点一下本机");
        }
    };

    private void handleLeds(byte[] data) {
        if (data == null || data.length == 0) return;
        // 协议栈通常已剥掉 Report ID；个别 ROM 会把 id 一起带上来，这里兼容两种
        int i = (composite && data.length > 1 && data[0] == HidKeyMap.REPORT_ID_KEYBOARD)
                ? 1 : 0;
        boolean caps = (data[i] & 0x02) != 0;
        Listener l = listener;
        if (l != null) main.post(() -> l.onCapsLed(caps));
    }

    /**
     * 主动向系统查询真实连接状态。回调在部分 ROM 上会丢失，这一步是「一直连接中」的兜底。
     */
    private void syncConnectedDevices(boolean publish) {
        final BluetoothHidDevice h = hid;
        if (h == null) return;
        io.execute(() -> {
            BluetoothDevice found = null;
            try {
                List<BluetoothDevice> connected = h.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) found = connected.get(0);
            } catch (Throwable t) {
                log("getConnectedDevices 失败: " + t);
            }
            if (found == null && lastHost != null) {
                try {
                    if (h.getConnectionState(lastHost) == BluetoothProfile.STATE_CONNECTED) {
                        found = lastHost;
                    }
                } catch (Throwable ignored) {
                }
            }
            final BluetoothDevice result = found;
            main.post(() -> {
                if (result != null) {
                    boolean wasDisconnected = host == null;
                    host = result;
                    lastHost = result;
                    lastConnectionState = BluetoothProfile.STATE_CONNECTED;
                    if (wasDisconnected) {
                        log("轮询发现已连接主机 " + nameOf(result));
                        state(ST_OK, "已连接", nameOf(result) + " · 可以打字了");
                        send(new byte[8]);
                    } else if (publish) {
                        state(ST_OK, "已连接", nameOf(result) + " · 可以打字了");
                    }
                } else if (publish && host != null) {
                    host = null;
                    lastConnectionState = BluetoothProfile.STATE_DISCONNECTED;
                    state(ST_WAIT, "已注册 · 等待主机连接",
                            "在主机上删除旧配对后重新搜索本机；配对期间手机保持本 App 前台");
                }
            });
        });
    }

    /** Activity 回到前台、蓝牙广播变化时调用：重新确认注册与连接状态。 */
    public void refresh() {
        if (shuttingDown) return;
        log("refresh：检查注册与连接状态");
        if (hid == null || !appRegistered) {
            start();
        } else {
            syncConnectedDevices(true);
        }
    }

    /** 注销后重新注册，强制刷新 HID SDP 记录（主机缓存了旧描述符时用）。 */
    public void forceReRegister() {
        final BluetoothHidDevice h = hid;
        if (h == null) {
            start();
            return;
        }
        state(ST_WAIT, "正在重新注册 HID", "注销旧 SDP 记录，稍后重新注册…");
        io.execute(() -> {
            try {
                h.unregisterApp();
            } catch (Throwable t) {
                log("unregisterApp 失败: " + t);
            }
            appRegistered = false;
            host = null;
            main.postDelayed(() -> {
                if (!shuttingDown) {
                    log("重新执行 registerApp");
                    registerApp();
                }
            }, 700);
        });
    }

    /** 反射调用隐藏 API removeBond()，清掉本机侧配对记录。 */
    public boolean removeBond(BluetoothDevice device) {
        if (device == null) return false;
        try {
            java.lang.reflect.Method m = device.getClass().getMethod("removeBond");
            Object r = m.invoke(device);
            log("removeBond(" + nameOf(device) + ") -> " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            log("removeBond 反射失败: " + t);
            return false;
        }
    }

    private void scheduleReconnect(final BluetoothDevice device) {
        if (device == null || ++reconnectTries > 4) return;
        main.postDelayed(() -> {
            BluetoothHidDevice h = hid;
            if (h == null || !appRegistered || manualDisconnect || host != null) return;
            BluetoothDevice target = lastHost != null ? lastHost : device;
            try {
                h.connect(target);
                log("自动重连 " + nameOf(target));
            } catch (Throwable t) {
                Log.w(TAG, "reconnect failed", t);
            }
        }, 1600L * reconnectTries);
    }

    /** 复合模式下键盘报告带 ID 1；仅键盘模式或 boot 协议下不带 ID（传 0）。 */
    private int keyboardReportId() {
        return composite && !bootMode ? HidKeyMap.REPORT_ID_KEYBOARD : 0;
    }

    /** 复合模式下鼠标报告带 ID 2；boot 协议下用 3 字节 boot mouse 报告，不带 ID。 */
    private int mouseReportId() {
        return composite && !bootMode ? HidKeyMap.REPORT_ID_MOUSE : 0;
    }

    /** 发送一条 8 字节键盘输入报告。 */
    public void send(final byte[] report) {
        if (report == null) return;
        lastReport = report.clone();
        sendRaw(keyboardReportId(), report);
    }

    /**
     * 提交一帧触控板/鼠标输入。这里<b>不直接发送</b>，只把位移交给合并泵：
     * 长时间积压的历史位移会被截断而不是全量补发，主机指针才不会「先卡顿、再瞬移」；
     * 正常的蓝牙连接间隔抖动则完全不丢位移，避免移动中「时不时停一下」。
     *
     * @param buttons bit0 左键 / bit1 右键 / bit2 中键（保持按下需持续置位，松开要发 0）
     * @param dx      横向相对位移
     * @param dy      纵向相对位移
     * @param wheel   纵向滚轮
     * @param pan     横向滚轮（AC Pan）
     */
    public void sendMouse(int buttons, int dx, int dy, int wheel, int pan) {
        if (!composite) return;   // 仅键盘兼容模式下不发送鼠标报告
        mousePump.submit(buttons, dx, dy, wheel, pan);
    }

    /** 预热/保活：强制发一条（通常是零位移）报告，提前唤醒蓝牙中断通道。 */
    public void warmMouse() {
        if (!composite) return;
        // 刚发过报告说明链路还是热的，重复预热只会多一次跨进程调用，
        // 反而拖慢手势开始后的头几帧位移
        if (SystemClock.uptimeMillis() - mousePump.lastSendAt() < MouseReportPump.IDLE_MS) return;
        mousePump.kick();
    }

    /** 清空待发位移并释放所有鼠标按键（切页 / 退后台时调用）。 */
    public void resetMouse() {
        mousePump.reset();
    }

    /** 触控板页可见时开启链路保活，离开页签时关闭。 */
    public void setMouseKeepAlive(boolean on) {
        if (mouseKeepAlive == on) return;
        mouseKeepAlive = on;
        main.removeCallbacks(mouseKeepAliveTask);
        if (on) {
            mousePump.kick();
            main.postDelayed(mouseKeepAliveTask, MOUSE_KEEPALIVE_MS);
        }
    }

    /** 在鼠标发送线程上真正组包并调用 sendReport；不再套第二层线程池队列。 */
    private void writeMouseReport(int buttons, int dx, int dy, int wheel, int pan) {
        if (!composite) return;
        final BluetoothHidDevice h = hid;
        if (h == null) return;
        final BluetoothDevice d = resolveHost(h);
        if (d == null) return;
        boolean boot = bootMode;
        byte[] r = new byte[boot ? HidKeyMap.REPORT_LEN_MOUSE_BOOT
                : HidKeyMap.REPORT_LEN_MOUSE];
        r[0] = (byte) (buttons & 0x07);
        r[1] = (byte) HidKeyMap.clampRel(dx);
        r[2] = (byte) HidKeyMap.clampRel(dy);
        if (!boot) {
            r[3] = (byte) HidKeyMap.clampRel(wheel);
            r[4] = (byte) HidKeyMap.clampRel(pan);
        }
        // 只记住按钮状态供主机 GET_REPORT 读取；相对位移绝不缓存，避免被重放
        lastMouseButtons = buttons & 0x07;
        try {
            if (h.sendReport(d, mouseReportId(), r)) {
                mouseSendFailures = 0;
                lastMouseSendOkAt = SystemClock.uptimeMillis();
            } else {
                // 蓝牙 HID 中断通道有流控：偶发 false 只代表这一帧没发出去，不是断连。
                // v1.7.1 在这里清空 host 并同步重连，导致之后每一帧都要额外走一次
                // getConnectedDevices() 跨进程调用，还会刷状态栏 —— 正是移动中
                // 「时不时卡一下」的主因。现在本帧直接丢弃即可（相对位移本就可丢），
                // 只有持续失败才在 UI 线程异步、低频地兜底重同步一次。
                int fails = ++mouseSendFailures;
                long now = SystemClock.uptimeMillis();
                if (fails >= 30 || (lastMouseSendOkAt > 0 && now - lastMouseSendOkAt > 3000L)) {
                    mouseSendFailures = 0;
                    main.post(() -> {
                        if (!shuttingDown) syncConnectedDevices(true);
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "sendReport(mouse) failed", t);
        }
    }

    /** 取当前主机；回调丢失时用 getConnectedDevices 兜底。 */
    private BluetoothDevice resolveHost(BluetoothHidDevice h) {
        BluetoothDevice d = host;
        if (d == null) {
            try {
                List<BluetoothDevice> connected = h.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    d = connected.get(0);
                    host = d;
                    lastHost = d;
                }
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    /** 统一发送入口：键盘报告仍在单线程 io 上排队，保证按键顺序。 */
    private void sendRaw(final int reportId, final byte[] data) {
        if (data == null) return;
        io.execute(() -> {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            BluetoothDevice d = resolveHost(h);
            if (d == null) return;
            try {
                boolean ok = h.sendReport(d, reportId, data);
                if (!ok) {
                    // 与鼠标报告同理：偶发 false 只是蓝牙流控，清空 host 会让后续每一帧
                    // 都额外走一次 getConnectedDevices()，反而制造卡顿。
                    // 这里只做低频异步重同步，保留已确认的 host 指针，也不刷诊断日志。
                    long now = SystemClock.uptimeMillis();
                    if (now - lastKeyboardResyncAt > 3000L) {
                        lastKeyboardResyncAt = now;
                        main.post(() -> {
                            if (!shuttingDown) syncConnectedDevices(true);
                        });
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "sendReport failed", t);
            }
        });
    }

    /** 发送一个短按（组合键 / 输入法切换用）：按下 → 110ms 后抬起。 */
    public void sendTap(int mods, int... usages) {
        byte[] report = new byte[HidKeyMap.REPORT_LEN_KEYBOARD];
        report[0] = (byte) mods;
        for (int i = 0; i < Math.min(usages.length, 6); i++) {
            report[2 + i] = (byte) usages[i];
        }
        send(report);
        // 保持足够长的和弦时间，主机输入法热键才能可靠识别 Ctrl+空格
        main.postDelayed(() -> send(new byte[HidKeyMap.REPORT_LEN_KEYBOARD]), 110);
    }

    /** 切换「键盘+触控板 / 仅键盘」描述符并重新注册（必须重新配对才会在主机生效）。 */
    public void setComposite(boolean enabled) {
        if (composite == enabled) return;
        composite = enabled;
        bootMode = false;
        log("切换 HID 模式为 " + (enabled ? "键盘+触控板" : "仅键盘") + "，重新注册 SDP");
        forceReRegister();
    }

    public boolean isComposite() {
        return composite;
    }

    /** 当前是否已连到指定主机，设备列表用它标注「（当前）」。 */
    public boolean isConnectedTo(BluetoothDevice d) {
        BluetoothDevice c = host;
        return d != null && c != null && d.equals(c);
    }

    /** 列出已配对设备（需要 BLUETOOTH_CONNECT 权限）。 */
    public List<BluetoothDevice> getBondedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
            if (bonded != null) out.addAll(bonded);
        } catch (Throwable t) {
            Log.w(TAG, "getBondedDevices failed", t);
        }
        Collections.sort(out, Comparator.comparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * 连接指定主机。若当前已连着另一台，会先自动断开旧主机再连接新主机，
     * 不需要用户先手动点一次「断开」。
     */
    public void connect(final BluetoothDevice device) {
        if (device == null) return;
        final BluetoothHidDevice h = hid;
        if (h == null || !appRegistered) {
            lastHost = device;
            state(ST_WAIT, "正在初始化", "HID 设备注册完成后请再点一次连接");
            start();
            return;
        }
        final BluetoothDevice current = host;
        if (current != null && !current.equals(device)) {
            // 已连接另一台主机：自动断开它，再发起新连接
            manualDisconnect = true;   // 防止断开回调走「自动重连回旧主机」分支
            host = null;
            lastHost = device;         // 兜底同步时以新目标为准
            state(ST_WAIT, "正在切换设备…",
                    "断开 " + nameOf(current) + " → 连接 " + nameOf(device));
            io.execute(() -> {
                try {
                    h.disconnect(current);
                    log("切换设备：先断开 " + nameOf(current));
                } catch (Throwable t) {
                    Log.w(TAG, "disconnect old host failed", t);
                }
                // 快速连点时只保留最后一次目标，避免多个连接请求互相打架
                if (pendingSwitchConnect != null) main.removeCallbacks(pendingSwitchConnect);
                pendingSwitchConnect = () -> {
                    pendingSwitchConnect = null;
                    // 切换过程中保持 manualDisconnect=true：旧主机迟到的断开回调
                    // 才不会误走自动重连分支、把刚连上的新主机抢走
                    connectNow(device, true);
                };
                main.postDelayed(pendingSwitchConnect, 600L);
            });
            return;
        }
        connectNow(device, false);
    }

    /** 真正发起连接；切换设备的路径会在旧主机断开后延迟调用这里。 */
    private void connectNow(final BluetoothDevice device, final boolean fromSwitch) {
        if (!fromSwitch) manualDisconnect = false;
        lastHost = device;
        final BluetoothHidDevice h = hid;
        if (h == null || !appRegistered) {
            state(ST_WAIT, "正在初始化", "HID 设备注册完成后请再点一次连接");
            start();
            return;
        }
        state(ST_WAIT, "正在连接…", nameOf(device) + " · 最多等待 10 秒");
        io.execute(() -> {
            try {
                int st = h.getConnectionState(device);
                log("connect 前状态 " + nameOf(device) + " = " + stateName(st));
                if (st == BluetoothProfile.STATE_CONNECTED) {
                    main.post(() -> {
                        host = device;
                        state(ST_OK, "已连接", nameOf(device) + " · 可以打字了");
                        send(new byte[8]);
                    });
                    return;
                }
                boolean ok = h.connect(device);
                log("connect(" + nameOf(device) + ") -> " + ok);
                main.post(() -> {
                    if (!ok) {
                        state(ST_ERROR, "连接请求被拒绝",
                                "主机端需先删除配对重新配对；或点「诊断」查看真实状态");
                    } else {
                        pollConnect(device, 0);
                    }
                });
            } catch (Throwable t) {
                log("connect 异常: " + t);
                main.post(() -> state(ST_ERROR, "连接异常", String.valueOf(t.getMessage())));
            }
        });
    }

    /** 回调不来时靠 getConnectionState 判定，10 秒后给出明确诊断而不是无限「连接中」。 */
    private void pollConnect(final BluetoothDevice device, final int round) {
        if (round > 12) return;
        main.postDelayed(() -> {
            if (shuttingDown) return;
            if (host != null) return; // 回调已处理
            final BluetoothHidDevice h = hid;
            if (h == null) return;
            io.execute(() -> {
                int st;
                try {
                    st = h.getConnectionState(device);
                } catch (Throwable t) {
                    st = BluetoothProfile.STATE_DISCONNECTED;
                }
                final int s = st;
                main.post(() -> {
                    if (host != null || shuttingDown) return;
                    if (s == BluetoothProfile.STATE_CONNECTED) {
                        host = device;
                        lastHost = device;
                        lastConnectionState = s;
                        log("轮询确认已连接 " + nameOf(device));
                        state(ST_OK, "已连接", nameOf(device) + " · 可以打字了");
                        send(new byte[8]);
                    } else if (round >= 12) {
                        lastConnectionState = s;
                        log("连接超时，最终状态 " + stateName(s));
                        state(ST_ERROR, "主机未建立 HID 通道",
                                "两端删除/忘记配对 → 手机保持本 App 前台 → 主机重新搜索配对（详见「帮助」）");
                    } else {
                        pollConnect(device, round + 1);
                    }
                });
            });
        }, 800);
    }

    public void disconnect() {
        manualDisconnect = true;
        BluetoothHidDevice h = hid;
        BluetoothDevice d = host;
        if (h != null && d != null) {
            try {
                h.disconnect(d);
                log("主动断开 " + nameOf(d));
            } catch (Throwable ignored) {
            }
        }
        host = null;
        state(ST_WAIT, "已断开", "点「设备」重新连接主机");
    }

    /** 完全停止：注销 HID、关闭代理、注销广播。前台服务自行 stopSelf。 */
    public void shutdown() {
        shuttingDown = true;
        log("shutdown");
        unregister();
        if (receiverRegistered) {
            try {
                ctx.unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
    }

    private void unregister() {
        BluetoothHidDevice h = hid;
        if (h != null) {
            try {
                h.unregisterApp();
            } catch (Throwable ignored) {
            }
            try {
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h);
            } catch (Throwable ignored) {
            }
        }
        hid = null;
        appRegistered = false;
        proxyRequested = false;
        host = null;
        lastConnectionState = BluetoothProfile.STATE_DISCONNECTED;
    }

    // ---------------------------------------------------------------- 广播

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i == null ? null : i.getAction();
            if (action == null) return;
            BluetoothDevice d = null;
            try {
                d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            } catch (Throwable ignored) {
            }
            log("广播 " + action.replace("android.bluetooth.", "")
                    + (d == null ? "" : " " + nameOf(d)));
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) return;
            main.postDelayed(() -> {
                if (!shuttingDown) syncConnectedDevices(true);
            }, 700);
        }
    };

    private void ensureReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(stateReceiver, f);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            log("注册广播接收器失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 诊断

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") / ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        try {
            sb.append("本机蓝牙名称：").append(adapter == null ? "无" : adapter.getName()).append('\n');
            sb.append("蓝牙开关：").append(adapter != null && adapter.isEnabled() ? "开" : "关").append('\n');
        } catch (Throwable t) {
            sb.append("本机蓝牙信息读取失败：").append(t).append('\n');
        }
        sb.append("BLUETOOTH_CONNECT：")
                .append(hasConnectPermission() ? "已授予" : "未授予 ⚠").append('\n');
        sb.append("前台服务：").append(HidForegroundService.isRunning() ? "运行中" : "未运行 ⚠")
                .append(" / 常驻通知：")
                .append(HidForegroundService.isForegroundOk() ? "已启动" : "启动失败 ⚠").append('\n');
        sb.append("HID Device 代理：").append(hid == null ? "未连接 ⚠" : "已连接").append('\n');
        sb.append("registerApp() 返回：").append(registerReturn).append('\n');
        sb.append("HID 注册状态：").append(appRegistered ? "已注册" : "未注册 ⚠").append('\n');
        sb.append("HID 模式：").append(composite
                ? "键盘(ID1)+触控板(ID2) 复合描述符" : "仅键盘（无 Report ID）")
                .append(bootMode ? " / 主机 boot 协议" : "").append('\n');
        BluetoothHidDevice h = hid;
        if (h != null && lastHost != null) {
            try {
                lastConnectionState = h.getConnectionState(lastHost);
            } catch (Throwable ignored) {
            }
        }
        sb.append("真实连接状态：").append(stateName(lastConnectionState)).append('\n');
        sb.append("当前主机：").append(host == null ? "无" : nameOf(host)).append('\n');
        try {
            List<BluetoothDevice> bonded = getBondedDevices();
            sb.append("已配对设备：").append(bonded.size()).append(" 台\n");
            for (BluetoothDevice d : bonded) {
                sb.append("  · ").append(nameOf(d)).append(" [").append(d.getAddress())
                        .append("] ").append(bondName(d.getBondState())).append('\n');
            }
        } catch (Throwable t) {
            sb.append("已配对设备读取失败：").append(t).append('\n');
        }
        sb.append("\n最近事件：\n");
        synchronized (events) {
            if (events.isEmpty()) sb.append("  （无）\n");
            for (String e : events) sb.append("  ").append(e).append('\n');
        }
        sb.append("\n提示：主机显示「手机」图标是正常的（由蓝牙设备类别 CoD 决定），"
                + "能打字才算成功。\n若连接状态未连接：两端删除配对 → 手机保持本 App 前台 → "
                + "由主机重新搜索配对。\n"
                + "★ v1.7.0 起描述符改为「键盘+触控板」复合设备，升级后必须两端忘记设备重新配对一次，"
                + "否则主机仍按旧的纯键盘/手机记录解析，触控板不会出现。\n");
        return sb.toString();
    }

    private static String bondName(int s) {
        switch (s) {
            case BluetoothDevice.BOND_BONDED: return "已配对";
            case BluetoothDevice.BOND_BONDING: return "配对中";
            default: return "未配对";
        }
    }

    public static String stateName(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED: return "已连接 (2)";
            case BluetoothProfile.STATE_CONNECTING: return "连接中 (1)";
            case BluetoothProfile.STATE_DISCONNECTED: return "未连接 (0)";
            case BluetoothProfile.STATE_DISCONNECTING: return "断开中 (3)";
            default: return "未知 (" + s + ")";
        }
    }

    private void log(String s) {
        String line;
        synchronized (timeFmt) {
            line = timeFmt.format(new Date()) + "  " + s;
        }
        synchronized (events) {
            events.addLast(line);
            while (events.size() > 14) events.removeFirst();
        }
        Log.i(TAG, s);
    }

    // ---------------------------------------------------------------- 工具

    public String nameOf(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? d.getAddress() : n;
        } catch (Throwable t) {
            return "未知设备";
        }
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void publishState() {
        if (host != null) state(ST_OK, "已连接", nameOf(host) + " · 可以打字了");
        else if (appRegistered) state(ST_WAIT, "已注册 · 等待主机连接",
                "在主机上删除旧配对后重新搜索本机；配对期间手机保持本 App 前台");
        else state(ST_WAIT, "正在初始化", "注册蓝牙 HID 键盘设备…");
    }

    private void state(int level, String title, String detail) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(level, title, detail);
        });
    }
}
