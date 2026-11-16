package com.btkeyboard.hid;

import static com.btkeyboard.hid.HidKeyMap.*;
import static com.btkeyboard.hid.KeyDef.*;

/** 键盘静态布局数据。每行内部按 weight 分配宽度，各行独立归一化。 */
public final class KeyboardLayouts {

    public static final int TAB_KEYBOARD = 0;   // 手机输入法风格大键主键盘（默认）
    public static final int TAB_TOUCHPAD = 1;   // 模拟触控板（相对鼠标）
    public static final int TAB_FUNCTIONS = 2;  // 编辑/导航区 + 数字小键盘
    public static final int TAB_SYMBOLS = 3;
    public static final int TAB_SHORTCUTS = 4;

    private KeyboardLayouts() {}

    private static KeyDef[] row(KeyDef... keys) {
        return keys;
    }

    /**
     * 手机输入法风格的主键盘：每行约 10 单位宽、只有 5 行（第 1 行数字较矮），
     * 字母键宽度从全盘的 1/15 提升到约 1/10，行数从 6 行减到 5 行，键面约为原来的 2 倍。
     * 数字行与 Q~P 一一对齐，方便直接输入数字而不必切换页签。
     */
    public static KeyDef[][] phone() {
        return new KeyDef[][]{
                // 数字行（渲染时行高权重 0.72）
                row(k("1", K_1, 1), k("2", K_2, 1), k("3", K_3, 1), k("4", K_4, 1),
                        k("5", K_5, 1), k("6", K_6, 1), k("7", K_7, 1), k("8", K_8, 1),
                        k("9", K_9, 1), k("0", K_0, 1)),

                row(k("Q", K_Q, 1), k("W", K_W, 1), k("E", K_E, 1), k("R", K_R, 1),
                        k("T", K_T, 1), k("Y", K_Y, 1), k("U", K_U, 1), k("I", K_I, 1),
                        k("O", K_O, 1), k("P", K_P, 1)),

                // 左右各留半个键宽，与安卓自带键盘一样整体居中
                row(blank(0.5f),
                        k("A", K_A, 1), k("S", K_S, 1), k("D", K_D, 1), k("F", K_F, 1),
                        k("G", K_G, 1), k("H", K_H, 1), k("J", K_J, 1), k("K", K_K, 1),
                        k("L", K_L, 1), blank(0.5f)),

                // 左侧 Shift、右侧退格，与安卓自带键盘一致
                row(mod("⇧", MOD_LSHIFT, 1.5f),
                        k("Z", K_Z, 1), k("X", K_X, 1), k("C", K_C, 1), k("V", K_V, 1),
                        k("B", K_B, 1), k("N", K_N, 1), k("M", K_M, 1),
                        k("⌫", K_BACKSPACE, 1.5f)),

                // 底行：页签入口 / 粘滞修饰 / 标点 / 大空格 / 大写 / 清除 / 回车
                row(tab("符号", TAB_SYMBOLS, 1.2f),
                        mod("Ctrl", MOD_LCTRL, 1.05f),
                        mod("Alt", MOD_LALT, 1.05f),
                        mod("Win", MOD_LGUI, 1.05f),
                        k(",", K_COMMA, 0.95f),
                        k("空格", K_SPACE, 2.9f),
                        k(".", K_DOT, 0.95f),
                        act("Caps", KIND_CAPS, 1.05f),
                        act("CLR", KIND_CLEAR, 1.05f),
                        k("↵", K_ENTER, 1.75f))
        };
    }

    // 原「全盘」6 行 × 15 单位布局已按需求移除；Esc / F1–F12 / PrtSc 等仍在
    // symbols() 的第 5、6 行，编辑导航键在 edit()，左右修饰键可由 phone() 的三态键代替。

    /** 右侧编辑/导航区：6 行 × 3 键。 */
    public static KeyDef[][] edit() {
        return new KeyDef[][]{
                row(k("Ins", K_INSERT, 1), k("Home", K_HOME, 1), k("PgUp", K_PAGEUP, 1)),
                row(k("Del", K_DELETE, 1), k("End", K_END, 1), k("PgDn", K_PAGEDOWN, 1)),
                row(blank(1), k("↑", K_UP, 1), blank(1)),
                row(k("←", K_LEFT, 1), k("↓", K_DOWN, 1), k("→", K_RIGHT, 1)),
                row(k("ScrLk", K_SCROLLLOCK, 1), k("Pause", K_PAUSE, 1), k("Menu", 0x65, 1)),
                row(k("Tab", K_TAB, 1), k("Esc", K_ESC, 1), k("↵", K_ENTER, 1))
        };
    }

    /** 数字小键盘：6 行 × 4 单位宽。 */
    public static KeyDef[][] numpad() {
        return new KeyDef[][]{
                row(k("Num", K_NUMLOCK, 1), k("/", K_KP_DIV, 1),
                        k("*", K_KP_MUL, 1), k("-", K_KP_SUB, 1)),
                row(k("7", K_KP_7, 1), k("8", K_KP_8, 1), k("9", K_KP_9, 1),
                        k("+", K_KP_ADD, 1)),
                row(k("4", K_KP_4, 1), k("5", K_KP_5, 1), k("6", K_KP_6, 1),
                        k("+", K_KP_ADD, 1)),
                row(k("1", K_KP_1, 1), k("2", K_KP_2, 1), k("3", K_KP_3, 1),
                        k("↵", K_KP_ENTER, 1)),
                row(k("0", K_KP_0, 2), k(".", K_KP_DOT, 1), k("↵", K_KP_ENTER, 1)),
                row(k("⌫", K_BACKSPACE, 1), k("Esc", K_ESC, 1),
                        k("F12", K_F12, 1), k("Tab", K_TAB, 1))
        };
    }

    /** 符号页：上档符号与常用编辑键。 */
    public static KeyDef[][] symbols() {
        return new KeyDef[][]{
                row(s("~", K_GRAVE, 1), s("!", K_1, 1), s("@", K_2, 1), s("#", K_3, 1),
                        s("$", K_4, 1), s("%", K_5, 1), s("^", K_6, 1), s("&", K_7, 1),
                        s("*", K_8, 1), s("(", K_9, 1), s(")", K_0, 1)),
                row(s("_", K_MINUS, 1), s("+", K_EQUAL, 1),
                        s("{", K_LBRACKET, 1), s("}", K_RBRACKET, 1),
                        k("[", K_LBRACKET, 1), k("]", K_RBRACKET, 1),
                        s("|", K_BACKSLASH, 1), k("\\", K_BACKSLASH, 1),
                        s(":", K_SEMICOLON, 1), k(";", K_SEMICOLON, 1),
                        s("\"", K_APOSTROPHE, 1)),
                row(k("'", K_APOSTROPHE, 1), s("<", K_COMMA, 1), s(">", K_DOT, 1),
                        k(",", K_COMMA, 1), k(".", K_DOT, 1), s("?", K_SLASH, 1),
                        k("/", K_SLASH, 1), k("`", K_GRAVE, 1),
                        k("-", K_MINUS, 1), k("=", K_EQUAL, 1), k("Space", K_SPACE, 1)),
                row(k("Tab", K_TAB, 1), k("Esc", K_ESC, 1), k("⌫", K_BACKSPACE, 1),
                        k("Del", K_DELETE, 1), k("Home", K_HOME, 1), k("End", K_END, 1),
                        k("PgUp", K_PAGEUP, 1), k("PgDn", K_PAGEDOWN, 1),
                        k("←", K_LEFT, 1), k("↑", K_UP, 1), k("→", K_RIGHT, 1)),
                row(k("F1", K_F1, 1), k("F2", K_F2, 1), k("F3", K_F3, 1), k("F4", K_F4, 1),
                        k("F5", K_F5, 1), k("F6", K_F6, 1), k("F7", K_F7, 1), k("F8", K_F8, 1),
                        k("F9", K_F9, 1), k("F10", K_F10, 1), k("F11", K_F11, 1)),
                row(k("F12", K_F12, 1), k("Ins", K_INSERT, 1), k("PrtSc", K_PRINTSCREEN, 1),
                        k("ScrLk", K_SCROLLLOCK, 1), k("Pause", K_PAUSE, 1),
                        k("Enter", K_ENTER, 1), k("↓", K_DOWN, 1),
                        combo("Ctrl+C", MOD_LCTRL, 1, K_C),
                        combo("Ctrl+V", MOD_LCTRL, 1, K_V),
                        combo("Ctrl+Z", MOD_LCTRL, 1, K_Z),
                        act("CLR", KIND_CLEAR, 1))
        };
    }

    /** 快捷页：一键发送常用组合键。 */
    public static KeyDef[][] shortcuts() {
        return new KeyDef[][]{
                row(combo("Ctrl+C 复制", MOD_LCTRL, 1, K_C),
                        combo("Ctrl+V 粘贴", MOD_LCTRL, 1, K_V),
                        combo("Ctrl+X 剪切", MOD_LCTRL, 1, K_X),
                        combo("Ctrl+A 全选", MOD_LCTRL, 1, K_A)),
                row(combo("Ctrl+Z 撤销", MOD_LCTRL, 1, K_Z),
                        combo("Ctrl+Y 重做", MOD_LCTRL, 1, K_Y),
                        combo("Ctrl+S 保存", MOD_LCTRL, 1, K_S),
                        combo("Ctrl+F 查找", MOD_LCTRL, 1, K_F)),
                row(combo("Ctrl+P 打印", MOD_LCTRL, 1, K_P),
                        combo("Ctrl+N 新建", MOD_LCTRL, 1, K_N),
                        combo("Ctrl+O 打开", MOD_LCTRL, 1, K_O),
                        combo("Ctrl+W 关闭", MOD_LCTRL, 1, K_W)),
                row(combo("Alt+Tab 切窗", MOD_LALT, 1, K_TAB),
                        combo("Alt+F4 关窗", MOD_LALT, 1, K_F4),
                        combo("Win+D 桌面", MOD_LGUI, 1, K_D),
                        combo("Win+E 资源", MOD_LGUI, 1, K_E)),
                row(combo("Win+R 运行", MOD_LGUI, 1, K_R),
                        combo("Win+L 锁屏", MOD_LGUI, 1, K_L),
                        combo("Win+Tab 视图", MOD_LGUI, 1, K_TAB),
                        combo("Win+S 搜索", MOD_LGUI, 1, K_S)),
                row(combo("Ctrl+Alt+Del", MOD_LCTRL | MOD_LALT, 1, K_DELETE),
                        combo("Ctrl+Shift+Esc", MOD_LCTRL | MOD_LSHIFT, 1, K_ESC),
                        combo("Win+Shift+S 截图", MOD_LGUI | MOD_LSHIFT, 1, K_S),
                        combo("PrtSc 截屏", 0, 1, K_PRINTSCREEN)),

                // 输入法/语言切换：一键发送完整和弦（保证主机看到足够长的 Ctrl 按下）
                row(combo("Ctrl+空格 输入法", MOD_LCTRL, 1, K_SPACE),
                        combo("Win+空格 语言", MOD_LGUI, 1, K_SPACE),
                        combo("Shift+空格 全半角", MOD_LSHIFT, 1, K_SPACE),
                        combo("Ctrl+. 中英标点", MOD_LCTRL, 1, K_DOT))
        };
    }
}
