package com.yuneko.jeicleaner.client;

import java.util.Locale;

/**
 * 拼音首字母索引（统一搜索的「拼音档」）。
 *
 * <p>HANZI 与 INITIALS 由 pypinyin 离线生成，覆盖 \u4E00-\u9FA5 的常用汉字，
 * 按 Unicode 码位升序，可二分查找。数据拆在 PinyinData1..3 里，便于维护。
 * 长度不一致会直接抛异常。
 */
public final class PinyinIndex {

    private static final String HANZI =
            PinyinData1.HANZI + PinyinData2.HANZI + PinyinData3.HANZI;

    private static final String INITIALS =
            PinyinData1.INITIALS + PinyinData2.INITIALS + PinyinData3.INITIALS;

    static {
        if (HANZI.length() != INITIALS.length()) {
            throw new IllegalStateException(
                    "PinyinIndex 表损坏: HANZI=" + HANZI.length() + " INITIALS=" + INITIALS.length());
        }
    }

    private PinyinIndex() {
    }

    /** 汉字 -> 拼音首字母（小写）；非汉字或查不到返回 0 */
    public static char initialOf(char c) {
        if (c < 0x4E00 || c > 0x9FA5) return 0;
        int lo = 0, hi = HANZI.length() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            char m = HANZI.charAt(mid);
            if (m == c) return INITIALS.charAt(mid);
            if (m > c) hi = mid - 1;
            else lo = mid + 1;
        }
        return 0;
    }

    /** 整串转首字母串：汉字转拼音首字母，非汉字原样保留并小写 */
    public static String toInitials(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char ini = initialOf(c);
            sb.append(ini != 0 ? ini : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** query 是否是 text 首字母串的子序列（jyxb 命中「经验修补」）；query 为空视为命中 */
    public static boolean initialsMatch(String text, String query) {
        if (query == null || query.isEmpty()) return true;
        if (text == null) return false;
        String q = query.toLowerCase(Locale.ROOT);
        String initials = toInitials(text);
        int qi = 0;
        for (int i = 0; i < initials.length() && qi < q.length(); i++) {
            if (initials.charAt(i) == q.charAt(qi)) qi++;
        }
        return qi == q.length();
    }
}
