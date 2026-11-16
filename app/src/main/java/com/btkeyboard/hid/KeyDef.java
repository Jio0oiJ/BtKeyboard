package com.btkeyboard.hid;

/** 键盘上一个按键的静态定义（布局由这些定义数据驱动生成）。 */
public final class KeyDef {

    public static final int KIND_NORMAL = 0;    // 普通键：按下/抬起
    public static final int KIND_MOD = 1;       // 粘滞修饰键：点一下切换按住状态
    public static final int KIND_CAPS = 2;      // 本 App 内大写锁定
    public static final int KIND_CLEAR = 3;     // 清除全部修饰与按下状态
    public static final int KIND_SHORTCUT = 4;  // 一键发送组合键
    public static final int KIND_TAB = 5;       // 切换面板
    public static final int KIND_BLANK = 6;     // 占位空格

    public final String label;
    public final int usage;
    public final boolean shift;
    public final float weight;
    public final int kind;
    public final int modBit;
    public final int comboMods;
    public final int[] comboKeys;
    public final int tabIndex;

    private KeyDef(String label, int usage, boolean shift, float weight, int kind,
                   int modBit, int comboMods, int[] comboKeys, int tabIndex) {
        this.label = label;
        this.usage = usage;
        this.shift = shift;
        this.weight = weight;
        this.kind = kind;
        this.modBit = modBit;
        this.comboMods = comboMods;
        this.comboKeys = comboKeys;
        this.tabIndex = tabIndex;
    }

    /** 普通键 */
    public static KeyDef k(String label, int usage, float weight) {
        return new KeyDef(label, usage, false, weight, KIND_NORMAL, 0, 0, null, -1);
    }

    /** 需要 Shift 的上档字符键 */
    public static KeyDef s(String label, int usage, float weight) {
        return new KeyDef(label, usage, true, weight, KIND_NORMAL, 0, 0, null, -1);
    }

    /** 粘滞修饰键 */
    public static KeyDef mod(String label, int modBit, float weight) {
        return new KeyDef(label, 0, false, weight, KIND_MOD, modBit, 0, null, -1);
    }

    /** 特殊动作键 */
    public static KeyDef act(String label, int kind, float weight) {
        return new KeyDef(label, 0, false, weight, kind, 0, 0, null, -1);
    }

    /** 快捷组合键 */
    public static KeyDef combo(String label, int mods, float weight, int... usages) {
        return new KeyDef(label, 0, false, weight, KIND_SHORTCUT, 0, mods, usages, -1);
    }

    /** 面板切换键 */
    public static KeyDef tab(String label, int tabIndex, float weight) {
        return new KeyDef(label, 0, false, weight, KIND_TAB, 0, 0, null, tabIndex);
    }

    /** 空白占位 */
    public static KeyDef blank(float weight) {
        return new KeyDef("", 0, false, weight, KIND_BLANK, 0, 0, null, -1);
    }
}
