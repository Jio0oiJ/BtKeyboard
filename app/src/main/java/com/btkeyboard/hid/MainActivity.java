package com.btkeyboard.hid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 蓝牙 HID 键盘 + 模拟触控板的界面与按键状态机。 */
public class MainActivity extends Activity implements HidKeyboard.Listener {

    private static final int REQ_PERMISSION = 1;
    private static final int REQ_ENABLE = 2;
    private static final int REQ_DISCOVERABLE = 3;

    private static final String HELP =
            "【v1.7.0 升级必读：需要重新配对一次】\n"
                    + "· 本版把 HID 描述符改成「键盘 + 触控板/鼠标」复合设备，主机缓存的旧描述符不会自动刷新。\n"
                    + "· 升级后请：主机删除/忘记这台手机 → 手机点「诊断 → 清除配对」→ 由主机重新搜索配对；\n"
                    + "  Windows 还要在设备管理器「显示隐藏的设备」里删掉残留的灰色 HID 设备。\n"
                    + "· 否则键盘可能还能用，但触控板不会出现。若某主机对复合设备兼容不佳，\n"
                    + "  可在设置区点「键鼠模式」切回「仅键盘」（同样需要重新配对）。\n\n"
                    + "【为什么主机把设备显示成「手机」】\n"
                    + "· 蓝牙设备图标由手机的「设备类别 CoD」决定，普通 App 无权修改，所以 Windows 里常显示「手机」，这本身不代表失败。\n"
                    + "· 真正决定能不能当键盘用的是 HID SDP 记录：主机设备「属性 → 服务」里应出现「人机接口设备 (HID)」，设备管理器里应出现「HID 键盘设备」。\n"
                    + "· 但如果配对时本 App 不在前台、或还没显示「已注册」，Android 系统会立刻注销 HID 注册，主机就只缓存到一台普通手机的服务 —— 图标是手机且完全打不了字。\n\n"
                    + "【正确的配对顺序（重要）】\n"
                    + "1. 手机：打开本 App，授予「附近的设备」权限，等右上角状态出现「已注册 · 等待主机连接」；通知栏出现「蓝牙键盘运行中」常驻通知。\n"
                    + "2. 主机：先删除/忘记这台手机。\n"
                    + "   Windows：设置 → 蓝牙和其他设备 → 找到手机 → 删除设备；\n"
                    + "   再到「设备管理器 → 查看 → 显示隐藏的设备」，删掉残留的灰色 HID / 未知蓝牙设备。\n"
                    + "3. 手机：点「配对」，允许「可被发现」（5 分钟）。\n"
                    + "4. 主机：添加蓝牙设备 → 搜索并选这台手机的蓝牙名称 → 完成配对。\n"
                    + "   ★ 全程由主机发起搜索；手机不要切到系统蓝牙设置，保持本 App 在前台、屏幕别锁。\n"
                    + "5. 状态变「已连接」即可打字；点「设备」选中主机发起连接。已连着另一台主机时直接选新主机即可，会自动断开旧主机再连接，不用先点「断开」。\n\n"
                    + "【一直显示「连接中」】\n"
                    + "· 现在最多等 10 秒会给出明确诊断，不会再无限等待；已连上但回调丢失也会自动同步成「已连接」。\n"
                    + "· 绝大多数是主机缓存了旧配对（里面没有 HID 服务）→ 回到上面第 2 步重新配对。\n"
                    + "· 点「诊断」看「HID 注册状态」和「真实连接状态」；注册失效就点「重新注册 HID」。\n\n"
                    + "【顶部与状态】\n"
                    + "· 运行状态固定在最右上角（● + 一行文字），点一下可查看完整状态、诊断、重新注册 HID。\n"
                    + "· 界面只有顶栏与页签两行，键盘占满其余全部高度；点「收起 ▴」隐藏设置区并把顶栏压到最矮，再点「设置 ▾」展开。折叠状态会被记住，下次打开自动保持。\n\n"
                    + "【状态含义】\n"
                    + "· 已注册 · 等待主机连接 = HID 服务已就绪，等主机来连。\n"
                    + "· 已连接 = HID 虚拟线缆已建立，可以打字。\n"
                    + "· HID 注册已失效 / 被拒绝 = 系统因 App 退后台注销了注册，或另一个 HID 类 App 占用。点「重试」并确认常驻通知在。\n"
                    + "· 主机未建立 HID 通道 = 手机侧注册正常，但主机没连 HID；按正确配对顺序重来一次。\n\n"
                    + "【键盘布局】\n"
                    + "· 「键盘」页：参照安卓自带输入法的放大布局，独占整屏、只有 5 行，每行约 10 个键，键面约为标准全盘的 2 倍。\n"
                    + "  第 1 行是矮数字行（与 Q~P 对齐），下面三行是 QWERTY 26 字母（A 行居中、⇧ 与 ⌫ 在 Z 行两端），\n"
                    + "  底行是「符号 / Ctrl / Alt / Win / , / 大空格 / . / Caps / CLR / ↵」。\n"
                    + "· 「触控板」页：模拟相对鼠标，单指移动光标、轻点＝左键、长按后拖动＝拖拽、双指滑动＝滚轮。\n"
                    + "· 「功能/数字」页：编辑导航区（Ins/Home/PgUp/Del/End/PgDn/方向键等）与数字小键盘并排。\n"
                    + "· 「符号」页是上档符号与 F1–F12 / Esc 等功能键；「快捷」页是常用系统组合键。\n"
                    + "· 原「全盘」页已移除，F1–F12、Esc、PrtSc 等在「符号」页。\n"
                    + "· ⇧ / Ctrl / Alt / Win 是三态修饰键：\n"
                    + "  ① 点一下＝一次性（蓝色键帽），主机立刻看到该键按下，普通键全部抬起后才松开；\n"
                    + "  ② 400ms 内连点两下＝粘滞锁定（绿色键帽 + 字体加粗），持续保持，直到再点一下该键；\n"
                    + "  ③ 一次性状态下再点一下同键＝取消；多个修饰键可叠加（如 Ctrl + Alt）；CLR 清除全部。\n"
                    + "· 组合键：点 Ctrl → 点 C，等于 Ctrl+C；点 Ctrl → 点空格＝切换主机输入法。\n"
                    + "  「快捷」页末行还有「Ctrl+空格 输入法」等一键发送，和弦保持 110ms，主机识别更可靠。\n"
                    + "· Caps 是本 App 内的大写锁定：字母键自动附带 Shift，不改变主机的 CapsLock LED。\n"
                    + "· 支持多指同时按键，最多同时 6 个普通键（6KRO）；长按由主机系统自动重复。\n\n"
                    + "【触控板用法】\n"
                    + "· 单指滑动＝移动光标；轻点＝左键单击；长按约 240ms 后拖动＝按住左键拖拽。\n"
                    + "· 双指上下滑动＝纵向滚轮，左右滑动＝横向滚轮（AC Pan）。\n"
                    + "· 顶部控制条：左键 / 中键 / 右键（按住生效）、双击、拖拽锁（常按左键）、灵敏度切换。\n"
                    + "· 位移报告按约 100Hz 节流并累积，裁剪到 -127..127 后由协议栈自动加 Report ID 2。\n"
                    + "· 主机端会出现「HID 兼容鼠标」；若完全没反应，先确认已按开头的说明重新配对。\n\n"
                    + "【屏幕常亮】\n"
                    + "· 顶部「常亮 开」按钮默认开启：本界面在前台时屏幕不会自动熄灭，方便长时间打字。\n"
                    + "· 点一下变「常亮 关」即可恢复系统自动息屏；切到别的 App 后常亮自动失效，不影响后台耗电。\n\n"
                    + "【限制】\n"
                    + "· 需要 Android 9（API 28）+，且系统启用 Bluetooth HID Device profile；个别厂商 ROM 会关掉该能力（诊断里「HID Device 代理」会显示未连接）。\n"
                    + "· 同一时间只能有一个 App 注册 HID 外设。\n"
                    + "· 中文由主机自己的输入法完成，HID 只发送键码。\n"
                    + "· 要彻底停止：点通知里的「停止并注销」。仅按返回键退出界面不会断开键盘。\n"
                    + "· 默认强制横屏；「旋转」按钮可切换 强制横屏 / 强制竖屏 / 跟随系统，锁定方向时按钮为绿色。";

    private HidKeyboard hid;
    private FrameLayout keyboardHost;
    private LinearLayout tabBar;
    private TextView statusDot, statusTitle;
    private Button btnAwake, btnToggle, btnMode;
    private View topBar, toolbarScroll, topSpacer, statusBox;
    private final List<Button> tabButtons = new ArrayList<>();
    /** 当前触控板页的视图；离开该页时用它释放所有按住的鼠标键。 */
    private TouchpadView touchpad;

    private boolean toolbarCollapsed;
    private String lastTitle = "正在初始化…";
    private String lastDetail = "";
    private boolean keepAwake = true;
    private int currentTab = KeyboardLayouts.TAB_KEYBOARD;
    /** 0=跟随系统 1=强制横屏 2=强制竖屏；默认强制横屏 */
    private int orientationMode = 1;
    private boolean enableRequested;
    private boolean initialized;
    private float keyTextSize = 13f;
    private Vibrator vibrator;
    /** 边缘震动提示开关：切页签重建视图时保留用户选择。 */
    private boolean edgeBuzzEnabled = true;

    /** usage -> (按下次数 << 8) | 标志位；bit0 = 该键需要 Shift */
    private final LinkedHashMap<Integer, Integer> pressed = new LinkedHashMap<>();
    /** 修饰键三态：一次性 / 粘滞锁定 / 未按下，替代原来的 modMask 单一开关 */
    private final ModStateMachine mods = new ModStateMachine();
    private boolean capsOn;
    private final List<TextView> latchViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hid = HidKeyboard.get(this);
        keyboardHost = findViewById(R.id.keyboardHost);
        tabBar = findViewById(R.id.tabBar);
        statusDot = findViewById(R.id.statusDot);
        statusTitle = findViewById(R.id.statusTitle);
        statusBox = findViewById(R.id.statusBox);
        topBar = findViewById(R.id.topBar);
        toolbarScroll = findViewById(R.id.toolbarScroll);
        topSpacer = findViewById(R.id.topSpacer);
        btnAwake = findViewById(R.id.btnAwake);
        btnToggle = findViewById(R.id.btnToggle);
        btnMode = findViewById(R.id.btnMode);

        toolbarCollapsed = getSharedPreferences("btkeyboard_ui", MODE_PRIVATE)
                .getBoolean("toolbar_collapsed", false);
        // 默认强制横屏；用户手动切换过的话沿用上次选择
        orientationMode = getSharedPreferences("btkeyboard_ui", MODE_PRIVATE)
                .getInt("orientation_mode", 1);

        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        } catch (Throwable ignored) {
        }

        applyKeepAwake();
        applyToolbarState();
        recalcTextSize();
        buildTabs();
        rebuildKeyboard();
        wireToolbar();

        hid.setListener(this);
        ensurePermissionsThenStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android 12+ 不允许后台启动前台服务；Activity 已完全前台时再兜底启动一次
        if (hasBluetoothPermission() && !HidForegroundService.isRunning()) {
            HidForegroundService.start(this);
        }
        // 从系统蓝牙设置/锁屏返回时，系统可能已自动注销 HID，这里主动重新确认
        if (initialized) hid.refresh();
        hid.setMouseKeepAlive(currentTab == KeyboardLayouts.TAB_TOUCHPAD);
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseTouchpad();
        hid.setMouseKeepAlive(false);
    }

    private void releaseTouchpad() {
        if (touchpad != null) touchpad.releaseAll();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        releaseTouchpad();
        hid.setMouseKeepAlive(false);
        hid.setListener(null);
        // 不再注销 HID：前台服务继续保活，按返回键退出界面不应让键盘掉线。
        // 要彻底停止请点通知里的「停止并注销」。
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recalcTextSize();
        rebuildKeyboard();
    }

    // ---------------------------------------------------------------- 权限 / 蓝牙

    private void ensurePermissionsThenStart() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMISSION);
        } else {
            startHid();
        }
    }

    private void startHid() {
        initialized = true;
        // 前台服务是 HID 注册不被系统注销的前提
        if (!HidForegroundService.isRunning()) HidForegroundService.start(this);
        hid.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSION) startHid();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE) {
            enableRequested = false;
            startHid();
        } else {
            hid.refresh();
        }
    }

    private void requestDiscoverable() {
        String msg = "配对前请确认：\n\n"
                + "1. 右上角状态显示「已注册 · 等待主机连接」；\n"
                + "2. 通知栏有「蓝牙键盘运行中」常驻通知；\n"
                + "3. 主机端已「删除/忘记」这台手机的旧配对（Windows 还需在设备管理器删掉隐藏的旧 HID 设备）；\n"
                + "4. 由主机发起搜索，手机保持本 App 在前台、不要锁屏或切到系统蓝牙设置。\n\n"
                + "当前 HID 注册：" + (hid.isRegistered() ? "已注册" : "未注册 ⚠ 将先重新注册");
        new AlertDialog.Builder(this)
                .setTitle("重新配对引导")
                .setMessage(msg)
                .setPositiveButton("已就绪，开始", (d, w) -> {
                    if (!hid.isRegistered()) hid.forceReRegister();
                    doDiscoverable();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDiscoverable() {
        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        try {
            startActivityForResult(i, REQ_DISCOVERABLE);
            toast("请在主机的蓝牙设置里搜索本机蓝牙名称并配对");
        } catch (Throwable t) {
            toast("无法打开「可被发现」设置：" + t.getMessage());
        }
    }

    private void showDiagnostics() {
        TextView tv = new TextView(this);
        tv.setText(hid.diagnostics());
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(getResources().getColor(R.color.text));
        tv.setTextIsSelectable(true);
        int p = dp(16);
        tv.setPadding(p, dp(10), p, dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("连接诊断")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("重新注册 HID", (d, w) -> {
                    hid.forceReRegister();
                    toast("已重新注册 HID；请在主机侧重新搜索/连接");
                })
                .setNegativeButton("清除配对", (d, w) -> showClearBondPicker())
                .show();
    }

    private void showClearBondPicker() {
        final List<BluetoothDevice> devices = hid.getBondedDevices();
        if (devices.isEmpty()) {
            toast("暂无已配对设备");
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) names[i] = hid.nameOf(devices.get(i));
        new AlertDialog.Builder(this)
                .setTitle("清除本机配对（主机侧也要删除）")
                .setItems(names, (d, w) -> {
                    boolean ok = hid.removeBond(devices.get(w));
                    hid.forceReRegister();
                    toast(ok ? "配对已清除 → 主机侧也删除该设备 → 点「配对」重来"
                            : "自动清除失败，请到系统蓝牙设置里手动取消配对");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDevicePicker() {
        final List<BluetoothDevice> devices = hid.getBondedDevices();
        if (devices.isEmpty()) {
            toast("暂无已配对设备，请先点「配对」");
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            names[i] = hid.nameOf(d) + (hid.isConnectedTo(d) ? "（当前）" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择主机（切换时自动断开当前设备）")
                .setItems(names, (dialog, which) -> hid.connect(devices.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showHelp() {
        TextView tv = new TextView(this);
        tv.setText(HELP);
        tv.setTextSize(13f);
        tv.setTextColor(getResources().getColor(R.color.text));
        int p = dp(20);
        tv.setPadding(p, dp(12), p, dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("蓝牙键盘 · 使用说明")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------------------------------------------------------- 状态回调

    @Override
    public void onState(int level, String title, String detail) {
        int color = level == HidKeyboard.ST_OK ? R.color.ok
                : level == HidKeyboard.ST_ERROR ? R.color.danger : R.color.warn;
        statusDot.setTextColor(getResources().getColor(color));
        lastTitle = title == null ? "" : title;
        lastDetail = detail == null ? "" : detail;
        // 状态压缩成右上角一行，完整内容点状态框查看
        statusTitle.setText(lastDetail.isEmpty()
                ? lastTitle : lastTitle + " · " + lastDetail);
    }

    @Override
    public void onCapsLed(boolean on) {
        // 本 App 的 Caps 是「本地自动加 Shift」，不接管主机的 CapsLock LED，故此处不改动本地状态。
    }

    @Override
    public void onBluetoothDisabled() {
        runOnUiThread(() -> {
            if (enableRequested) return;
            enableRequested = true;
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Throwable t) {
                enableRequested = false;
                toast("请到系统设置里开启蓝牙");
            }
        });
    }

    // ---------------------------------------------------------------- 工具条 / 页签

    private void wireToolbar() {
        findViewById(R.id.btnPair).setOnClickListener(v -> requestDiscoverable());
        findViewById(R.id.btnDevices).setOnClickListener(v -> showDevicePicker());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> hid.disconnect());
        findViewById(R.id.btnDiag).setOnClickListener(v -> showDiagnostics());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            enableRequested = false;
            if (hid.isRegistered()) hid.forceReRegister();
            else ensurePermissionsThenStart();
        });
        btnAwake.setOnClickListener(v -> {
            keepAwake = !keepAwake;
            applyKeepAwake();
            updateAwakeButton();
            toast(keepAwake ? "使用时屏幕保持常亮" : "已关闭常亮，屏幕按系统设置自动熄灭");
        });
        updateAwakeButton();
        btnMode.setOnClickListener(v -> toggleHidMode());
        updateModeButton();
        findViewById(R.id.btnRotate).setOnClickListener(v -> {
            orientationMode = (orientationMode + 1) % 3;
            getSharedPreferences("btkeyboard_ui", MODE_PRIVATE)
                    .edit().putInt("orientation_mode", orientationMode).apply();
            applyOrientation();
            updateRotateButton();
            toast(orientationMode == 1 ? "已强制横屏"
                    : orientationMode == 2 ? "已强制竖屏" : "跟随系统方向");
        });
        // 启动时立即应用（默认强制横屏），并让按钮文字/颜色反映当前状态
        applyOrientation();
        updateRotateButton();
        btnToggle.setOnClickListener(v -> {
            toolbarCollapsed = !toolbarCollapsed;
            getSharedPreferences("btkeyboard_ui", MODE_PRIVATE)
                    .edit().putBoolean("toolbar_collapsed", toolbarCollapsed).apply();
            applyToolbarState();
            toast(toolbarCollapsed ? "设置区已收起，键盘区更大" : "设置区已展开");
        });
        statusBox.setOnClickListener(v -> showStatusDialog());
    }

    /**
     * 折叠时隐藏设置区并把顶栏压到 28dp；
     * topSpacer 接管横向剩余权重，保证状态框始终贴在最右上角。
     */
    private void applyToolbarState() {
        toolbarScroll.setVisibility(toolbarCollapsed ? View.GONE : View.VISIBLE);
        topSpacer.setVisibility(toolbarCollapsed ? View.VISIBLE : View.GONE);
        btnToggle.setText(getString(toolbarCollapsed
                ? R.string.settings_expand : R.string.settings_collapse));

        int h = dp(toolbarCollapsed ? 28 : 34);
        ViewGroup.LayoutParams lpBtn = btnToggle.getLayoutParams();
        lpBtn.height = h;
        btnToggle.setLayoutParams(lpBtn);
        ViewGroup.LayoutParams lpStatus = statusBox.getLayoutParams();
        lpStatus.height = h;
        statusBox.setLayoutParams(lpStatus);
        int pv = dp(toolbarCollapsed ? 2 : 5);
        topBar.setPadding(dp(8), pv, dp(8), pv);
    }

    /** 右上角状态只显示一行，点击查看完整状态、诊断或重新注册 HID。 */
    private void showStatusDialog() {
        String msg = lastTitle
                + (lastDetail.isEmpty() ? "" : "\n" + lastDetail)
                + "\n\n前台服务：" + (HidForegroundService.isRunning()
                ? "运行中（切后台不会掉线）" : "未运行 ⚠（切后台可能被系统注销 HID）");
        new AlertDialog.Builder(this)
                .setTitle("蓝牙键盘状态")
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .setNeutralButton("诊断", (d, w) -> showDiagnostics())
                .setNegativeButton("重新注册", (d, w) -> {
                    hid.forceReRegister();
                    toast("已重新注册 HID；请在主机侧重新连接");
                })
                .show();
    }

    /** FLAG_KEEP_SCREEN_ON 只在当前窗口可见时生效，退到后台自动释放，无需额外权限或 WakeLock。 */
    private void applyKeepAwake() {
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateAwakeButton() {
        btnAwake.setText(getString(keepAwake ? R.string.awake_on : R.string.awake_off));
        btnAwake.setTextColor(getResources().getColor(keepAwake ? R.color.ok : R.color.dim));
    }

    private void updateModeButton() {
        boolean combo = hid.isComposite();
        btnMode.setText(getString(combo ? R.string.mode_combo : R.string.mode_keyboard));
        btnMode.setTextColor(getResources().getColor(combo ? R.color.ok : R.color.warn));
    }

    /** 按当前模式应用屏幕方向：1=横屏 2=竖屏 0=跟随系统。 */
    private void applyOrientation() {
        switch (orientationMode) {
            case 1:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case 2:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
        }
    }

    /** 锁定方向=绿色、跟随系统=灰色，文字同步显示当前模式。 */
    private void updateRotateButton() {
        Button b = findViewById(R.id.btnRotate);
        int label = orientationMode == 1 ? R.string.rotate_landscape
                : orientationMode == 2 ? R.string.rotate_portrait : R.string.rotate_auto;
        b.setText(getString(label));
        b.setTextColor(getResources().getColor(
                orientationMode == 0 ? R.color.dim : R.color.ok));
    }

    /** 切换 HID 描述符会改变 SDP 记录，必须提醒用户两端重新配对。 */
    private void toggleHidMode() {
        final boolean next = !hid.isComposite();
        new AlertDialog.Builder(this)
                .setTitle(next ? "切换为「键盘 + 触控板」" : "切换为「仅键盘」兼容模式")
                .setMessage(next
                        ? "将重新注册为复合 HID 设备（键盘 Report ID 1 + 鼠标 Report ID 2）。\n\n"
                        + "⚠ 主机缓存的旧描述符不会自动更新：切换后请在主机端「忘记此设备」，\n"
                        + "手机点「诊断 → 清除配对」，再由主机重新搜索配对，触控板才会出现。"
                        : "将重新注册为纯键盘设备（无 Report ID），触控板页会停用但键盘不受影响。\n\n"
                        + "⚠ 同样需要两端忘记设备后重新配对。适合某些主机不兼容复合 HID 描述符时使用。")
                .setPositiveButton("切换并重新注册", (d, w) -> {
                    hid.setComposite(next);
                    updateModeButton();
                    toast(next ? "已切回键鼠模式；请在主机侧删除配对后重新配对"
                            : "已切到仅键盘模式；请在主机侧删除配对后重新配对");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void buildTabs() {
        String[] names = {"键盘", "触控板", "功能/数字", "符号", "快捷"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(11f);
            b.setSingleLine(true);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(0, 0, 0, 0);
            b.setBackgroundResource(R.drawable.btn_bg);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setOnClickListener(v -> switchTab(index));
            tabBar.addView(b, lp);
            tabButtons.add(b);
        }
        updateTabs();
    }

    private void updateTabs() {
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).setTextColor(getResources().getColor(
                    i == currentTab ? R.color.accent : R.color.dim));
        }
    }

    private void switchTab(int index) {
        if (index < 0 || index > KeyboardLayouts.TAB_SHORTCUTS || index == currentTab) return;
        // 切到触控板时把还挂着的一次性修饰键抬起，避免 ctrl 之类跨页签卡在主机上
        if (index == KeyboardLayouts.TAB_TOUCHPAD && mods.consumeOneShot()) {
            refreshLatchViews();
            flush();
        }
        currentTab = index;
        updateTabs();
        rebuildKeyboard();
    }

    // ---------------------------------------------------------------- 键盘视图

    private void recalcTextSize() {
        int w = getResources().getConfiguration().screenWidthDp;
        // 手机风格主键盘每行约 10 键（原全盘是 15 键），字号随键宽放大，上限防止横屏撑破键面
        keyTextSize = Math.max(13f, Math.min(22f, w / 36f));
    }

    private void rebuildKeyboard() {
        // 视图重建后旧按键收不到 ACTION_UP，先主动释放，避免主机上「卡键」
        if (!pressed.isEmpty()) {
            pressed.clear();
            flush();
        }
        // 离开触控板页时释放所有按住的鼠标键，避免主机上「卡住左键」
        releaseTouchpad();
        touchpad = null;
        keyboardHost.removeAllViews();
        latchViews.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        if (currentTab == KeyboardLayouts.TAB_KEYBOARD) {
            // 手机输入法风格：5 行大键独占整屏，数字行略矮，其余 4 行等分高度
            root.addView(section(KeyboardLayouts.phone(), 1f, keyTextSize * 1.18f,
                    new float[]{0.72f, 1f, 1f, 1f, 1f}));
        } else if (currentTab == KeyboardLayouts.TAB_TOUCHPAD) {
            root.addView(buildTouchpadPage(), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else if (currentTab == KeyboardLayouts.TAB_FUNCTIONS) {
            // 编辑导航区（每行 3 键）与数字小键盘（每行 4 键）并排，按 3:4 分配宽度使键面接近等宽
            root.addView(section(KeyboardLayouts.edit(), 0.43f, keyTextSize * 0.88f));
            root.addView(section(KeyboardLayouts.numpad(), 0.57f, keyTextSize * 0.88f));
        } else if (currentTab == KeyboardLayouts.TAB_SYMBOLS) {
            root.addView(section(KeyboardLayouts.symbols(), 1f, keyTextSize * 0.85f));
        } else {
            root.addView(section(KeyboardLayouts.shortcuts(), 1f, keyTextSize * 0.9f));
        }
        keyboardHost.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 只有触控板页保活蓝牙中断通道：提前唤醒链路，避免每次按下手指都要等首包
        hid.setMouseKeepAlive(currentTab == KeyboardLayouts.TAB_TOUCHPAD);
    }

    private LinearLayout section(KeyDef[][] rows, float weight, float textSize) {
        return section(rows, weight, textSize, null);
    }

    /** rowWeights 可让某些行矮一些（如主键盘顶部的数字行），null 表示所有行等高。 */
    private LinearLayout section(KeyDef[][] rows, float weight, float textSize,
                                 float[] rowWeights) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        for (int r = 0; r < rows.length; r++) {
            KeyDef[] keys = rows[r];
            float rowHeight = rowWeights != null && r < rowWeights.length ? rowWeights[r] : 1f;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, rowHeight));
            for (KeyDef kd : keys) {
                TextView tv = makeKey(kd, textSize);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, kd.weight);
                // 键间距放大到 2dp，配合大圆角更接近系统输入法键盘的观感
                int g = dp(2);
                lp.setMargins(g, g, g, g);
                line.addView(tv, lp);
            }
            col.addView(line);
        }
        return col;
    }

    /** 触控板页：顶部控制条 + 占满剩余高度的触摸区。 */
    private View buildTouchpadPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TouchpadView pad = new TouchpadView(this);
        pad.setBackgroundResource(R.drawable.touchpad_bg);
        pad.setSink(new TouchpadView.Sink() {
            @Override
            public void mouse(int buttons, int dx, int dy, int wheel, int pan) {
                hid.sendMouse(buttons, dx, dy, wheel, pan);
            }

            @Override
            public void warm() {
                hid.warmMouse();
            }

            @Override
            public void resetMouse() {
                hid.resetMouse();
            }

            @Override
            public void buzz() {
                MainActivity.this.buzz();
            }

            @Override
            public void edgeBuzz() {
                Haptic.edgeBuzz(vibrator);
            }
        });
        touchpad = pad;
        pad.setEdgeBuzzEnabled(edgeBuzzEnabled);

        bar.addView(mouseButton("左键", HidKeyMap.BTN_LEFT, pad));
        bar.addView(mouseButton("中键", HidKeyMap.BTN_MIDDLE, pad));
        bar.addView(mouseButton("右键", HidKeyMap.BTN_RIGHT, pad));

        Button dbl = toolButton("双击");
        dbl.setOnClickListener(v -> pad.doubleClick());
        bar.addView(dbl);

        final Button drag = toolButton("拖拽 关");
        drag.setOnClickListener(v -> {
            boolean on = pad.toggleDragLock();
            drag.setText(on ? "拖拽 开" : "拖拽 关");
            drag.setTextColor(getResources().getColor(on ? R.color.ok : R.color.dim));
        });
        bar.addView(drag);

        final Button sens = toolButton("灵敏度 " + pad.sensitivityLabel());
        sens.setOnClickListener(v -> sens.setText("灵敏度 " + pad.cycleSensitivity()));
        bar.addView(sens);

        final Button edge = toolButton(edgeBuzzEnabled ? "边缘 开" : "边缘 关");
        edge.setTextColor(getResources().getColor(edgeBuzzEnabled ? R.color.ok : R.color.dim));
        edge.setOnClickListener(v -> {
            edgeBuzzEnabled = !edgeBuzzEnabled;
            pad.setEdgeBuzzEnabled(edgeBuzzEnabled);
            edge.setText(edgeBuzzEnabled ? "边缘 开" : "边缘 关");
            edge.setTextColor(getResources().getColor(edgeBuzzEnabled ? R.color.ok : R.color.dim));
        });
        bar.addView(edge);

        page.addView(bar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        int g = dp(2);
        lp.setMargins(g, g, g, g);
        page.addView(pad, lp);
        return page;
    }

    private Button toolButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11f);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackgroundResource(R.drawable.btn_bg);
        b.setTextColor(getResources().getColor(R.color.text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private Button mouseButton(String text, int bit, TouchpadView pad) {
        Button b = toolButton(text);
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    pad.setButton(bit, true);
                    buzz();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    pad.setButton(bit, false);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    /** 按功能区分键帽底色：字母键最亮，修饰/功能键偏暗，回车与页签入口用强调蓝。 */
    private static int keyBackground(KeyDef kd) {
        switch (kd.kind) {
            case KeyDef.KIND_MOD:
            case KeyDef.KIND_CAPS:
            case KeyDef.KIND_CLEAR:
                return R.drawable.key_special_bg;
            case KeyDef.KIND_TAB:
                return R.drawable.key_accent_bg;
            default:
                if ("↵".equals(kd.label) || "Enter".equals(kd.label)) {
                    return R.drawable.key_accent_bg;
                }
                return R.drawable.key_bg;
        }
    }

    /**
     * 修饰键三态键帽：未按下=深色，一次性=蓝色，锁定=绿色且字体加粗。
     * 同一个 modBit 在不同页签可能有多个键（如「⇧」与「Shift」），统一按状态刷新。
     */
    private void applyModVisual(TextView tv, KeyDef kd) {
        int state = mods.state(kd.modBit);
        if (state == ModStateMachine.LOCKED) {
            tv.setBackgroundResource(R.drawable.key_latch_bg);
            tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else if (state == ModStateMachine.ONESHOT) {
            tv.setBackgroundResource(R.drawable.key_once_bg);
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        } else {
            tv.setBackgroundResource(keyBackground(kd));
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
    }

    private void applyCapsVisual(TextView tv) {
        tv.setBackgroundResource(capsOn ? R.drawable.key_latch_bg : R.drawable.key_special_bg);
    }

    /** 状态变化或视图重建后统一刷新所有粘滞键，避免只改到被点击的那一个 View。 */
    private void refreshLatchViews() {
        for (TextView tv : latchViews) {
            Object tag = tv.getTag();
            if (!(tag instanceof KeyDef)) continue;
            KeyDef kd = (KeyDef) tag;
            if (kd.kind == KeyDef.KIND_MOD) applyModVisual(tv, kd);
            else if (kd.kind == KeyDef.KIND_CAPS) applyCapsVisual(tv);
        }
    }

    private TextView makeKey(final KeyDef kd, float textSize) {
        TextView tv = new TextView(this);
        tv.setText(kd.label);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(getResources().getColor(R.color.text));

        int len = kd.label.length();
        float size = len >= 9 ? textSize * 0.60f
                : len >= 6 ? textSize * 0.70f
                : len >= 4 ? textSize * 0.80f : textSize;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);

        if (kd.kind == KeyDef.KIND_BLANK) {
            tv.setBackgroundColor(Color.TRANSPARENT);
            return tv;
        }

        // 视图重建后需要按当前状态重新着色，故把定义挂在 View 上供 refreshLatchViews 读取
        tv.setTag(kd);
        if (kd.kind == KeyDef.KIND_MOD) {
            applyModVisual(tv, kd);
            latchViews.add(tv);
        } else if (kd.kind == KeyDef.KIND_CAPS) {
            applyCapsVisual(tv);
            latchViews.add(tv);
        } else {
            tv.setBackgroundResource(keyBackground(kd));
        }

        tv.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    onKeyDown(kd, v);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    onKeyUp(kd, v);
                    return true;
                default:
                    return true;
            }
        });
        return tv;
    }

    // ---------------------------------------------------------------- 按键状态机

    private void onKeyDown(KeyDef kd, View v) {
        switch (kd.kind) {
            case KeyDef.KIND_NORMAL:
                press(kd.usage, kd.shift || (capsOn && isLetter(kd.usage)));
                break;
            case KeyDef.KIND_MOD:
                onModClick(kd.modBit);
                break;
            case KeyDef.KIND_CAPS:
                capsOn = !capsOn;
                refreshLatchViews();
                break;
            case KeyDef.KIND_CLEAR:
                clearAll();
                break;
            case KeyDef.KIND_SHORTCUT:
                hid.sendTap(kd.comboMods, kd.comboKeys);
                // 一键组合键同样算「按了其他键」，用完释放一次性修饰。
                // 但一次性位此前已写进报告，需等 sendTap 的 110ms 和弦发完再补抬起报告，
                // 否则会把组合键的修饰位提前抹掉。
                if (mods.consumeOneShot()) {
                    refreshLatchViews();
                    keyboardHost.postDelayed(() -> {
                        if (pressed.isEmpty()) flush();
                    }, 160);
                }
                break;
            case KeyDef.KIND_TAB:
                switchTab(kd.tabIndex);
                break;
            default:
                break;
        }
        buzz();
    }

    private void onKeyUp(KeyDef kd, View v) {
        if (kd.kind == KeyDef.KIND_NORMAL) release(kd.usage);
    }

    /** 点一下修饰键：单击→一次性，400ms 内同键再点→锁定，锁定态再点→恢复。 */
    private void onModClick(int bit) {
        mods.click(bit, SystemClock.uptimeMillis());
        refreshLatchViews();
        // 立刻把「修饰键按下」写进报告：主机先看到独立 keydown，再看到和弦，
        // 这样 Ctrl+空格 之类的输入法热键才会被识别（v1.6.0 缺了这一步）
        flush();
    }

    private void clearAll() {
        pressed.clear();
        mods.clear();
        capsOn = false;
        refreshLatchViews();
        flush();
    }

    private void press(int usage, boolean needsShift) {
        Integer old = pressed.get(usage);
        int value = old == null ? 0 : old;
        int count = (value >>> 8) + 1;
        int flags = (value & 0xFF) | (needsShift ? 1 : 0);
        pressed.put(usage, (count << 8) | flags);
        flush();
    }

    private void release(int usage) {
        Integer value = pressed.get(usage);
        if (value == null) return;
        int count = (value >>> 8) - 1;
        if (count <= 0) pressed.remove(usage);
        else pressed.put(usage, (count << 8) | (value & 0xFF));
        // 所有普通键都抬起后，一次性修饰自动松开；锁定的不受影响
        boolean consumed = pressed.isEmpty() && mods.consumeOneShot();
        flush();
        if (consumed) refreshLatchViews();
    }

    /** 把当前按下的键与修饰状态合成一条 8 字节 HID 报告。 */
    private void flush() {
        byte[] report = new byte[8];
        // 一次性位从点击起就进报告（主机先看到独立的 Ctrl keydown，输入法热键才能识别），
        // 直到下一个普通键抬起被消费；锁定位始终在报告里。
        int modifiers = mods.reportMods();
        for (Map.Entry<Integer, Integer> e : pressed.entrySet()) {
            if ((e.getValue() & 1) != 0) modifiers |= HidKeyMap.MOD_LSHIFT;
        }
        report[0] = (byte) modifiers;
        int i = 2;
        for (Integer usage : pressed.keySet()) {
            if (i >= 8) break;
            report[i++] = (byte) usage.intValue();
        }
        hid.send(report);
    }

    private static boolean isLetter(int usage) {
        return usage >= HidKeyMap.K_A && usage <= HidKeyMap.K_Z;
    }

    /** 点击反馈（10ms）：交给 Haptic 在后台线程执行，不阻塞 UI 触摸采样。 */
    private void buzz() {
        Haptic.buzz(vibrator);
    }

    // ---------------------------------------------------------------- 小工具

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
