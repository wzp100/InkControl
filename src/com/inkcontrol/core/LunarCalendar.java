package com.inkcontrol.core;

import java.util.Calendar;

/**
 * 农历换算（1900–2100）：内置经典农历数据表，无第三方依赖、无 Android 依赖，可宿主单测。
 * 每年一个 int：低 4 位为闰月月份（0 表示无闰月），0x10000 位为闰月大小（1=30 天），
 * 0x8000→0x10 十二位依次为正月…十二月大小（1=30 天，0=29 天）。
 * 锚点校准（设备 OEM Lunar 实证）：2026-10-24 = 丙午年九月十五；2026 年春节 = 2026-02-17。
 * 不直接支持闰月日（日期选择器无法表达闰月），但换算偏移量完整计入闰月。
 */
public final class LunarCalendar {
    private LunarCalendar() {}

    private static final int[] LUNAR = {
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63
    };

    /** 数据表基准公历日：1900-01-31（农历 1900 年正月初一）。 */
    private static final Calendar BASE;
    static {
        Calendar calendar = Calendar.getInstance();
        calendar.set(1900, Calendar.JANUARY, 31, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        BASE = calendar;
    }

    private static int leapMonth(int year) { return LUNAR[year - 1900] & 0xf; }

    private static int leapDays(int year) {
        return leapMonth(year) == 0 ? 0 : ((LUNAR[year - 1900] & 0x10000) != 0 ? 30 : 29);
    }

    private static int monthDays(int year, int month) {
        return (LUNAR[year - 1900] & (0x10000 >> month)) != 0 ? 30 : 29;
    }

    private static int yearDays(int year) {
        int sum = 348;                                    // 12 × 29
        for (int bit = 0x8000; bit > 0x8; bit >>= 1) if ((LUNAR[year - 1900] & bit) != 0) sum++;
        return sum + leapDays(year);
    }

    /** 农历（年/月/日，不含闰月）是否在表范围内且真实存在。 */
    public static boolean valid(int year, int month, int day) {
        return year >= 1900 && year <= 2100 && month >= 1 && month <= 12
                && day >= 1 && day <= monthDays(year, month);
    }

    /** 农历（年/月/日，不含闰月）→ 公历当天 00:00 的 Calendar；非法返回 null。 */
    public static Calendar toSolar(int year, int month, int day) {
        if (!valid(year, month, day)) return null;
        int offset = 0;
        for (int y = 1900; y < year; y++) offset += yearDays(y);
        int leap = leapMonth(year);
        boolean isLeap = false;
        for (int m = 1; m < month; m++) {
            int days;
            if (leap > 0 && m == leap + 1 && !isLeap) {
                m--;                                       // 同月号插入闰月，下一步按正式月处理
                isLeap = true;
                days = leapDays(year);
            } else {
                days = monthDays(year, m);
            }
            if (isLeap && m == leap + 1) isLeap = false;
            offset += days;
        }
        offset += day - 1;
        Calendar solar = (Calendar) BASE.clone();
        solar.add(Calendar.DAY_OF_YEAR, offset);
        return solar;
    }

    /** 公历（年/月/日）→ 农历 {年, 月, 日}（month 为 1–12，不含闰月）；超出 1900–2100 返回 null。 */
    public static int[] toLunar(int year, int month, int day) {
        Calendar solar = Calendar.getInstance();
        solar.set(year, month - 1, day, 0, 0, 0);
        solar.set(Calendar.MILLISECOND, 0);
        long offset = Math.round((solar.getTimeInMillis() - BASE.getTimeInMillis()) / 86400000.0);
        if (offset < 0) return null;
        int y = 1900, temp = 0;
        for (; y <= 2100; y++) {
            temp = yearDays(y);
            if (offset < temp) break;
            offset -= temp;
        }
        if (y > 2100) return null;
        int leap = leapMonth(y);
        boolean isLeap = false;
        int m = 1;
        for (; m < 13 && offset > 0; m++) {
            if (leap > 0 && m == leap + 1 && !isLeap) {
                m--;
                isLeap = true;
                temp = leapDays(y);
            } else {
                temp = monthDays(y, m);
            }
            if (isLeap && m == leap + 1) isLeap = false;
            offset -= temp;
        }
        if (offset == 0 && leap > 0 && m == leap + 1) {
            if (isLeap) isLeap = false;
            else { isLeap = true; m--; }
        }
        if (offset < 0) { offset += temp; m--; }
        return new int[]{y, m, (int) offset + 1};
    }
}
