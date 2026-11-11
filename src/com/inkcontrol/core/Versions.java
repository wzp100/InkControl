package com.inkcontrol.core;

/**
 * 版本号比较。墨控的版本形如 {@code 0.3.0-rc1}（主.次.修订-rc候选号）或正式版 {@code 0.3.0}，
 * 只按数字逐段比较，前缀 {@code v} 不参与；不带候选号的正式版比同号候选新。
 *
 * <p>之前「检测更新」只判断字符串相等，线上还停在 {@code 0.2.5-r23} 时，
 * 本机 {@code 0.2.6-r26} 会被报成「发现新版本 v0.2.5-r23」——方向正好反了。
 */
public final class Versions {
    private Versions() {}

    /** 把版本串里最多四段数字取出来：{@code 0.2.6-r26 → {0,2,6,26}}，前三段缺失按 0；没有第四段视为正式版。 */
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
        // 没有第四段（X.Y.Z 正式版）时排在同号的 -rcN 候选之后：0.3.0 比 0.3.0-rc1 新。
        if (at < out.length) out[out.length - 1] = Integer.MAX_VALUE;
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
