package com.btkeyboard.hid;

/** USB HID Keyboard/Keypad (usage page 0x07) 键码、以及键盘 / 鼠标的报告描述符。 */
public final class HidKeyMap {

    private HidKeyMap() {}

    /** 报告第 0 字节：修饰键位掩码 */
    public static final int MOD_LCTRL = 0x01;
    public static final int MOD_LSHIFT = 0x02;
    public static final int MOD_LALT = 0x04;
    public static final int MOD_LGUI = 0x08;
    public static final int MOD_RCTRL = 0x10;
    public static final int MOD_RSHIFT = 0x20;
    public static final int MOD_RALT = 0x40;
    public static final int MOD_RGUI = 0x80;

    public static final int K_A = 0x04, K_B = 0x05, K_C = 0x06, K_D = 0x07,
            K_E = 0x08, K_F = 0x09, K_G = 0x0A, K_H = 0x0B, K_I = 0x0C,
            K_J = 0x0D, K_K = 0x0E, K_L = 0x0F, K_M = 0x10, K_N = 0x11,
            K_O = 0x12, K_P = 0x13, K_Q = 0x14, K_R = 0x15, K_S = 0x16,
            K_T = 0x17, K_U = 0x18, K_V = 0x19, K_W = 0x1A, K_X = 0x1B,
            K_Y = 0x1C, K_Z = 0x1D;
    public static final int K_1 = 0x1E, K_2 = 0x1F, K_3 = 0x20, K_4 = 0x21,
            K_5 = 0x22, K_6 = 0x23, K_7 = 0x24, K_8 = 0x25, K_9 = 0x26,
            K_0 = 0x27;
    public static final int K_ENTER = 0x28, K_ESC = 0x29, K_BACKSPACE = 0x2A,
            K_TAB = 0x2B, K_SPACE = 0x2C, K_MINUS = 0x2D, K_EQUAL = 0x2E,
            K_LBRACKET = 0x2F, K_RBRACKET = 0x30, K_BACKSLASH = 0x31,
            K_SEMICOLON = 0x33, K_APOSTROPHE = 0x34, K_GRAVE = 0x35,
            K_COMMA = 0x36, K_DOT = 0x37, K_SLASH = 0x38, K_CAPS = 0x39;
    public static final int K_F1 = 0x3A, K_F2 = 0x3B, K_F3 = 0x3C, K_F4 = 0x3D,
            K_F5 = 0x3E, K_F6 = 0x3F, K_F7 = 0x40, K_F8 = 0x41, K_F9 = 0x42,
            K_F10 = 0x43, K_F11 = 0x44, K_F12 = 0x45;
    public static final int K_PRINTSCREEN = 0x46, K_SCROLLLOCK = 0x47, K_PAUSE = 0x48,
            K_INSERT = 0x49, K_HOME = 0x4A, K_PAGEUP = 0x4B, K_DELETE = 0x4C,
            K_END = 0x4D, K_PAGEDOWN = 0x4E, K_RIGHT = 0x4F, K_LEFT = 0x50,
            K_DOWN = 0x51, K_UP = 0x52;
    public static final int K_NUMLOCK = 0x53, K_KP_DIV = 0x54, K_KP_MUL = 0x55,
            K_KP_SUB = 0x56, K_KP_ADD = 0x57, K_KP_ENTER = 0x58,
            K_KP_1 = 0x59, K_KP_2 = 0x5A, K_KP_3 = 0x5B, K_KP_4 = 0x5C,
            K_KP_5 = 0x5D, K_KP_6 = 0x5E, K_KP_7 = 0x5F, K_KP_8 = 0x60,
            K_KP_9 = 0x61, K_KP_0 = 0x62, K_KP_DOT = 0x63;

    // ------------------------------------------------------------------ 报告格式

    /** 复合描述符里键盘输入报告的 Report ID；sendReport 的第二参数，data 中不含该字节。 */
    public static final int REPORT_ID_KEYBOARD = 1;
    /** 复合描述符里鼠标/触控板输入报告的 Report ID。 */
    public static final int REPORT_ID_MOUSE = 2;

    public static final int REPORT_LEN_KEYBOARD = 8;   // mods / reserved / 6 键码
    public static final int REPORT_LEN_MOUSE = 5;      // buttons / X / Y / Wheel / AC Pan
    public static final int REPORT_LEN_MOUSE_BOOT = 3; // boot 协议：buttons / X / Y

    public static final int BTN_LEFT = 0x01;
    public static final int BTN_RIGHT = 0x02;
    public static final int BTN_MIDDLE = 0x04;

    /** 相对位移/滚轮是 8 位有符号数，裁剪到 -127..127。 */
    public static int clampRel(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    /**
     * 默认描述符：复合 HID 设备（键盘 + 相对鼠标/触控板），带两个 Report ID。
     *
     * <p>Bluedroid 的 bta_hd_register_act 会扫描描述符中是否出现 0x85(Report ID)，
     * 有则置 use_report_id=true，HID 中断数据由协议栈自动在 payload 前面拼上 Report ID
     * （hidd_conn_send_data），所以 sendReport(device, 1|2, data) 的 data 不含 id。
     *
     * <p>报告 1 = 8 字节标准 boot keyboard；报告 2 = 5 字节鼠标：
     * bit0..2 左/右/中键，X/Y 为 -127..127 相对位移，Wheel 纵向滚轮，AC Pan 横向滚轮。
     * 键盘输出报告（LED）与输入同用 ID 1。
     */
    public static final byte[] REPORT_DESCRIPTOR = descriptor(
            // ---- Keyboard Application Collection ----
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x06,              // Usage (Keyboard)
            0xA1, 0x01,              // Collection (Application)
            0x85, 0x01,              //   Report ID (1)
            0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0,              //   Usage Minimum (Left Control)
            0x29, 0xE7,              //   Usage Maximum (Right GUI)
            0x15, 0x00,              //   Logical Minimum (0)
            0x25, 0x01,              //   Logical Maximum (1)
            0x75, 0x01,              //   Report Size (1)
            0x95, 0x08,              //   Report Count (8)
            0x81, 0x02,              //   Input (Data,Var,Abs)   <- 修饰键 1 字节
            0x95, 0x01,              //   Report Count (1)
            0x75, 0x08,              //   Report Size (8)
            0x81, 0x01,              //   Input (Const)          <- 保留 1 字节
            0x95, 0x05,              //   Report Count (5)
            0x75, 0x01,              //   Report Size (1)
            0x05, 0x08,              //   Usage Page (LEDs)
            0x19, 0x01,              //   Usage Minimum (Num Lock)
            0x29, 0x05,              //   Usage Maximum (Kana)
            0x91, 0x02,              //   Output (Data,Var,Abs)  <- LED 5 bit
            0x95, 0x01,              //   Report Count (1)
            0x75, 0x03,              //   Report Size (3)
            0x91, 0x01,              //   Output (Const)         <- LED 补齐 3 bit
            0x95, 0x06,              //   Report Count (6)
            0x75, 0x08,              //   Report Size (8)
            0x15, 0x00,              //   Logical Minimum (0)
            0x25, 0x65,              //   Logical Maximum (101)
            0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,              //   Usage Minimum (0)
            0x29, 0x65,              //   Usage Maximum (101)
            0x81, 0x00,              //   Input (Data,Array)     <- 6 个键码
            0xC0,                     // End Collection

            // ---- Mouse / Touchpad Application Collection ----
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x02,              // Usage (Mouse)
            0xA1, 0x01,              // Collection (Application)
            0x85, 0x02,              //   Report ID (2)
            0x09, 0x01,              //   Usage (Pointer)
            0xA1, 0x00,              //   Collection (Physical)
            0x05, 0x09,              //     Usage Page (Button)
            0x19, 0x01,              //     Usage Minimum (Button 1)
            0x29, 0x03,              //     Usage Maximum (Button 3)
            0x15, 0x00,              //     Logical Minimum (0)
            0x25, 0x01,              //     Logical Maximum (1)
            0x75, 0x01,              //     Report Size (1)
            0x95, 0x03,              //     Report Count (3)
            0x81, 0x02,              //     Input (Data,Var,Abs) <- 左/右/中键
            0x95, 0x01,              //     Report Count (1)
            0x75, 0x05,              //     Report Size (5)
            0x81, 0x01,              //     Input (Const)        <- 补齐 1 字节
            0x05, 0x01,              //     Usage Page (Generic Desktop)
            0x09, 0x30,              //     Usage (X)
            0x09, 0x31,              //     Usage (Y)
            0x09, 0x38,              //     Usage (Wheel)
            0x15, 0x81,              //     Logical Minimum (-127)
            0x25, 0x7F,              //     Logical Maximum (127)
            0x75, 0x08,              //     Report Size (8)
            0x95, 0x03,              //     Report Count (3)
            0x81, 0x06,              //     Input (Data,Var,Rel) <- X / Y / Wheel
            0x05, 0x0C,              //     Usage Page (Consumer Devices)
            0x0A, 0x38, 0x02,        //     Usage (AC Pan)
            0x15, 0x81,              //     Logical Minimum (-127)
            0x25, 0x7F,              //     Logical Maximum (127)
            0x75, 0x08,              //     Report Size (8)
            0x95, 0x01,              //     Report Count (1)
            0x81, 0x06,              //     Input (Data,Var,Rel) <- 横向滚轮
            0xC0,                     //   End Collection (Physical)
            0xC0                      // End Collection (Application)
    );

    /**
     * 兼容回退：纯键盘描述符（HID 1.11 Appendix B 标准 boot keyboard，63 字节）。
     * 无 Report ID，sendReport() 的 reportId 必须传 0。
     * 若某主机对复合描述符兼容不佳，可在设置里切回「仅键盘」模式重新注册。
     */
    public static final byte[] KEYBOARD_ONLY_DESCRIPTOR = descriptor(
            0x05, 0x01,              // Usage Page (Generic Desktop)
            0x09, 0x06,              // Usage (Keyboard)
            0xA1, 0x01,              // Collection (Application)
            0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0,              //   Usage Minimum (Left Control)
            0x29, 0xE7,              //   Usage Maximum (Right GUI)
            0x15, 0x00,              //   Logical Minimum (0)
            0x25, 0x01,              //   Logical Maximum (1)
            0x75, 0x01,              //   Report Size (1)
            0x95, 0x08,              //   Report Count (8)
            0x81, 0x02,              //   Input (Data,Var,Abs)   <- 修饰键
            0x95, 0x01,              //   Report Count (1)
            0x75, 0x08,              //   Report Size (8)
            0x81, 0x01,              //   Input (Const)          <- 保留字节
            0x95, 0x05,              //   Report Count (5)
            0x75, 0x01,              //   Report Size (1)
            0x05, 0x08,              //   Usage Page (LEDs)
            0x19, 0x01,              //   Usage Minimum (Num Lock)
            0x29, 0x05,              //   Usage Maximum (Kana)
            0x91, 0x02,              //   Output (Data,Var,Abs)  <- LED
            0x95, 0x01,              //   Report Count (1)
            0x75, 0x03,              //   Report Size (3)
            0x91, 0x01,              //   Output (Const)         <- LED 补齐
            0x95, 0x06,              //   Report Count (6)
            0x75, 0x08,              //   Report Size (8)
            0x15, 0x00,              //   Logical Minimum (0)
            0x25, 0x65,              //   Logical Maximum (101)
            0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,              //   Usage Minimum (0)
            0x29, 0x65,              //   Usage Maximum (101)
            0x81, 0x00,              //   Input (Data,Array)     <- 6 个键码
            0xC0                      // End Collection
    );

    private static byte[] descriptor(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }
}
