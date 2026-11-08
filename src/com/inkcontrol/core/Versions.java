package com.inkcontrol.core;

/**
 * 版本号比较。墨控的版本形如 {@code 0.2.6-r26}（主.次.修订-r构建号），
 * 只按数字逐段比较，前后缀（{@code v}、{@code -beta} 之类）不参与。
 *
 * <p>之前「检测更新」只判断字符串相等，线上还停在 {@code 0.2.5-r23} 时，
 * 本机 {@code 0.2.6-r26} 会被报成「发现新版本 v0.2.5-r23」——方向正好反了。
 */
public final class Versions {
    private Versions() {}

    /** 把版本串里最多四段数字取出来：{@code 0.2.6-r26 → {0,2,6,26}}，缺失段按 0。 */
    static int[] parts(String value) {
        int[] out = new int[4];
        if (value == null) return out;
        int at = 0;
        long current = 0;
        boolean digits = false;
        for (int i = 0; i <= value.length(); i++) {
            char c = i == value.length() ? ' ' : value.charAt(i);
            if (c >= '0' && c <= '9') {
                // 超长数字串只保留前段，避免溢出；版本号不会这么长，纯粹防御手改的标签。
                current = current * 10 + (c - '0');
                if (current > Integer.MAX_VALUE) current = Integer.MAX_VALUE;
                digits = true;
                continue;
            }
            if (digits) {
                if (at < out.length) out[at++] = (int) current;
                current = 0;
                digits = false;
            }
        }
        return out;
    }

    /** a 比 b 新返回正数，一样返回 0，a 比 b 旧返回负数。 */
    public static int compare(String a, String b) {
        int[] left = parts(a), right = parts(b);
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) return left[i] > right[i] ? 1 : -1;
        }
        return 0;
    }
}
