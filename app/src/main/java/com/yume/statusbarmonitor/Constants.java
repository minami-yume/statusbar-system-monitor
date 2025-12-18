package com.yume.statusbarmonitor;

public final class Constants {

    private Constants() {}

    // SharedPreferences 文件名
    public static final String PREFS_NAME = "app_settings";

    // SharedPreferences 键
    public static final String KEY_DATA1 = "data1_key";
    public static final String KEY_DATA2 = "data2_key";
    public static final String KEY_FONT_SIZE = "font_size";
    public static final String KEY_OFFSET = "offset";
    public static final String KEY_BITMAP_SIZE = "bitmap_size";
    public static final String KEY_REFRESH_RATE_POS = "refresh_rate_position";
    public static final String KEY_PADDING_X = "padding_x";
    public static final String KEY_PADDING_Y = "padding_y";
    public static final String KEY_FONT_CHOICE = "font_choice";
    public static final String KEY_DIVISOR = "divisor";

    // 字体文件名列表，与 arrays.xml 中的顺序对应
    public static final String[] FONT_FILENAMES = {
            null, // 默认字体 (索引 0)
            "fonts/ZurichBT-ExtraCondensed.otf" // 你的自定义字体 (索引 1)
    };
}