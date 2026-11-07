package com.inkcontrol.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** 功能元数据是界面、业务校验和平台执行的共同契约。 */
public final class FeatureDefinition {
    public enum ApplyMode { HOT, REBOOT }
    public final String id, group, title, description, role, dependency;
    public final ApplyMode mode;
    public final boolean experimental;
    /**
     * 配置里没有这个键时按「开」处理。只给「显示设备状态」下的四项显示开关用：
     * 用户第一次打开「显示设备状态」时四类信息应当都在，之后各自关掉的才记住。
     */
    public final boolean defaultOn;
    public final List<String> values, labels;

    public FeatureDefinition(String id, String group, String title, String description,
            String role, String dependency, ApplyMode mode, boolean experimental,
            String[] values, String[] labels) {
        this(id, group, title, description, role, dependency, mode, experimental, values, labels, false);
    }

    public FeatureDefinition(String id, String group, String title, String description,
            String role, String dependency, ApplyMode mode, boolean experimental,
            String[] values, String[] labels, boolean defaultOn) {
        this.id = id;
        this.group = group;
        this.title = title;
        this.description = description;
        this.role = role;
        this.dependency = dependency;
        this.mode = mode;
        this.experimental = experimental;
        this.defaultOn = defaultOn;
        this.values = Collections.unmodifiableList(Arrays.asList(values));
        this.labels = Collections.unmodifiableList(Arrays.asList(labels));
        if (values.length == 0 || values.length != labels.length) throw new IllegalArgumentException(id);
    }

    /** 首个取值若是占位标记（@ 开头），默认值就是空串：不内置任何应用名单，由用户自己选。 */
    public String defaultValue() {
        String first = values.get(0);
        return first.startsWith("@") ? "" : first;
    }
    /** 是否接受按温区自定义的停留曲线（值形如 curve:150,175,...，共 TEMPERATURE_BANDS 段）。 */
    public boolean acceptsCurve() { return values.contains(CURVE_TOKEN); }
    /** 是否接受手动输入的正整数（如触发次数）。 */
    public boolean acceptsNumber() { return values.contains(NUMBER_TOKEN); }
    /** 是否接受逗号分隔的应用包名名单。 */
    public boolean acceptsApps() { return values.contains(APPS_TOKEN); }
    /** 是否接受纯包名逗号列表（没有白／黑名单模式的那种）。 */
    public boolean acceptsPackages() { return values.contains(PACKAGES_TOKEN); }
    public void validate(String value) {
        if (CURVE_TOKEN.equals(value) || NUMBER_TOKEN.equals(value) || APPS_TOKEN.equals(value)
                || COUNTDOWNS_TOKEN.equals(value) || PATH_TOKEN.equals(value) || FONT_TOKEN.equals(value)
                || PANEL_TOKEN.equals(value) || PACKAGE_TOKEN.equals(value) || PACKAGES_TOKEN.equals(value))
            throw new IllegalArgumentException("不支持的选项：" + title);
        if (values.contains(value)) return;
        // 纯页面项（@panel）没有自己的取值，配置里永远是空串
        if (acceptsPanel() && value.isEmpty()) return;
        if (acceptsCurve() && curve(value) != null) return;
        if (acceptsNumber() && number(value) != null) return;
        if (acceptsApps() && appFilter(value) != null) return;
        // 「目标阅读器」存的是纯包名列表（如 io.legado.app.release,com.x），复用 apps() 的分段校验。
        if (acceptsPackages() && apps(value) != null) return;
        if (acceptsCountdowns() && countdowns(value) != null) return;
        if ((acceptsPath() || acceptsFont()) && path(value) != null) return;
        // 单个包名（「截屏便签」那一格换成的应用）：空串表示没指定
        if (acceptsPackage() && (value.isEmpty() || packageName(value))) return;
        throw new IllegalArgumentException("不支持的选项：" + title);
    }

    /** 目录里的占位值；本身不是合法配置，只表示允许 curve: 取值。 */
    public static final String CURVE_TOKEN = "@curve";
    /** 目录里的占位值；本身不是合法配置，只表示允许输入 1–999 的整数。 */
    public static final String NUMBER_TOKEN = "@number";
    public static final int NUMBER_MIN = 1, NUMBER_MAX = 999;
    /** 解析自定义整数；只接受 NUMBER_MIN–NUMBER_MAX 的纯数字，否则返回 null。 */
    public static Integer number(String value) {
        if (value == null || !value.matches("[0-9]{1,3}")) return null;
        int parsed = Integer.parseInt(value);
        return parsed >= NUMBER_MIN && parsed <= NUMBER_MAX ? parsed : null;
    }
    /** 目录里的占位值；本身不是合法配置，只表示允许逗号分隔的包名名单。 */
    public static final String APPS_TOKEN = "@apps";
    /** 目录里的占位值；本身不是合法配置，只表示允许「纯包名逗号列表」（不带黑白名单模式）。 */
    public static final String PACKAGES_TOKEN = "@packages";
    /** 目录里的占位值；本身不是合法配置，只表示允许最多 COUNTDOWNS_MAX 组「事项;日期;农历;显示」倒数日清单。 */
    public static final String COUNTDOWNS_TOKEN = "@countdowns";
    /** 倒数日最多组数。 */
    public static final int COUNTDOWNS_MAX = 3;
    /** 倒数日事项名最长字符数。 */
    public static final int COUNTDOWN_ITEM_MAX = 12;
    /** 新格式每组字段数：事项;日期;农历;显示。 */
    public static final int COUNTDOWN_FIELDS = 4;
    /** 旧格式每组字段数：事项;日期（农历=否、显示=是，兼容读取）。 */
    public static final int COUNTDOWN_LEGACY_FIELDS = 2;
    /** 是否接受「事项;日期;农历;显示」清单（最多 3 组，事项可空，日期为空则该组停用）。 */
    public boolean acceptsCountdowns() { return values.contains(COUNTDOWNS_TOKEN); }
    /**
     * 目录里的占位值；本身不是合法配置。表示这一项没有自己的配置值——它只是一个页面
     * （「显示信息修改」把四项显示开关收在一起），进入后改的是别的键。
     */
    public static final String PANEL_TOKEN = "@panel";
    public boolean acceptsPanel() { return values.contains(PANEL_TOKEN); }
    /** 目录里的占位值；本身不是合法配置，只表示允许输入目录路径或字体文件名。 */
    public static final String PATH_TOKEN = "@path";
    /** 目录里的占位值；本身不是合法配置，只表示允许记录最近应用的字体文件名。 */
    public static final String FONT_TOKEN = "@font";
    /** 路径/文件名字符数上限。 */
    public static final int PATH_MAX = 200;
    public boolean acceptsPath() { return values.contains(PATH_TOKEN); }
    public boolean acceptsFont() { return values.contains(FONT_TOKEN); }
    /** 字体目录或字体文件名：空串表示用默认值；非空时不允许等号、分号、控制字符与 shell 元字符。 */
    public static String path(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        if (value.length() > PATH_MAX) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
            if (c == '=' || c == ';' || c == '$' || c == '`' || c == '"' || c == '\''
                    || c == '|' || c == '&' || c == '>' || c == '<' || c == '*' || c == '?'
                    || c == '!' || c == '\\') return null;
        }
        return value;
    }
    /** 解析倒数日清单值：空串（无事项）、旧 6 段「事项;日期」×3 或新 12 段「事项;日期;农历;显示」×3。 */
    public static String countdowns(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        String[] parts = value.split(";", -1);
        boolean legacy = parts.length == COUNTDOWNS_MAX * COUNTDOWN_LEGACY_FIELDS;
        if (!legacy && parts.length != COUNTDOWNS_MAX * COUNTDOWN_FIELDS) return null;
        int step = legacy ? COUNTDOWN_LEGACY_FIELDS : COUNTDOWN_FIELDS;
        for (int i = 0; i < parts.length; i += step) {
            String item = parts[i].trim();
            // 事项名不得含 '='：会破坏配置的 key=value 行格式（后端 ic_validate 同样拒绝）
            if (item.length() > COUNTDOWN_ITEM_MAX || item.indexOf('=') >= 0) return null;
            String date = parts[i + 1].trim();
            boolean lunar = !legacy && "1".equals(parts[i + 2].trim());
            if (!date.isEmpty() && targetDate(date, lunar) == null) return null;
            if (!legacy && !(parts[i + 2].trim() + parts[i + 3].trim()).matches("[01]{2}")) return null;
        }
        return value;
    }
    /** 有效倒数日组（日期非空且合法），按目标公历日升序；旧格式按公历、默认显示。 */
    public static List<CountdownItem> countdownList(String value) {
        List<CountdownItem> result = new ArrayList<>();
        if (value == null || value.isEmpty()) return result;
        String[] parts = value.split(";", -1);
        boolean legacy = parts.length == COUNTDOWNS_MAX * COUNTDOWN_LEGACY_FIELDS;
        if (!legacy && parts.length != COUNTDOWNS_MAX * COUNTDOWN_FIELDS) return result;
        int step = legacy ? COUNTDOWN_LEGACY_FIELDS : COUNTDOWN_FIELDS;
        for (int i = 0; i < parts.length; i += step) {
            String date = parts[i + 1].trim();
            boolean lunar = !legacy && "1".equals(parts[i + 2].trim());
            boolean shown = legacy || !"0".equals(parts[i + 3].trim());
            if (!date.isEmpty() && targetDate(date, lunar) != null)
                result.add(new CountdownItem(parts[i].trim(), date, lunar, shown));
        }
        result.sort((a, b) -> Long.compare(targetMillis(a), targetMillis(b)));
        return result;
    }
    private static long targetMillis(CountdownItem item) {
        Calendar target = targetDate(item.date, item.lunar);
        return target == null ? Long.MAX_VALUE : target.getTimeInMillis();
    }
    /** 目标公历日：公历日期直接解析；农历日期按所选农历年/月/日换算（不支持闰月）。无效返回 null。 */
    public static Calendar targetDate(String date, boolean lunar) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        int year = Integer.parseInt(date.substring(0, 4));
        int month = Integer.parseInt(date.substring(5, 7));
        int day = Integer.parseInt(date.substring(8, 10));
        return lunar ? LunarCalendar.toSolar(year, month, day) : parseDate(date);
    }
    /** 一个倒数日组：事项名（可空）、目标日期（yyyy-MM-dd）、是否按农历解读、是否显示。 */
    public static final class CountdownItem {
        public final String item, date;
        public final boolean lunar, shown;
        public CountdownItem(String item, String date, boolean lunar, boolean shown) {
            this.item = item == null ? "" : item;
            this.date = date == null ? "" : date;
            this.lunar = lunar;
            this.shown = shown;
        }
    }
    /** 目录里的占位值；本身不是合法配置，只表示允许输入单个应用包名。 */
    public static final String PACKAGE_TOKEN = "@package";
    public boolean acceptsPackage() { return values.contains(PACKAGE_TOKEN); }

    /** 把 yyyy-MM-dd 解析为当天 00:00 的 Calendar；不是真实日期返回 null。 */
    public static Calendar parseDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            format.setLenient(false);
            java.util.Date parsed = format.parse(value);
            if (parsed == null) return null;
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(parsed);
            return calendar;
        } catch (java.text.ParseException error) {
            return null;
        }
    }
    /** 名单最多容纳的包名数量。 */
    public static final int APPS_MAX = 200;
    /** 解析应用名单：空串表示空名单；非空时每段必须是合法包名，否则返回 null。 */
    public static String apps(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        String[] parts = value.split(",", -1);
        if (parts.length > APPS_MAX) return null;
        for (String part : parts) if (!packageName(part)) return null;
        return value;
    }
    public static final String WHITELIST = "whitelist", BLACKLIST = "blacklist";
    /** 解析应用过滤取值：空串（默认）或 模式;白名单;黑名单；旧格式 模式[:名单] 仍可读取。 */
    public static String appFilter(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        int semi = value.indexOf(';');
        if (semi < 0) {
            int colon = value.indexOf(':');
            String mode = colon < 0 ? value : value.substring(0, colon);
            if (!mode.equals(WHITELIST) && !mode.equals(BLACKLIST)) return null;
            String list = colon < 0 ? "" : value.substring(colon + 1);
            if (apps(list) == null) return null;
            boolean white = mode.equals(WHITELIST);
            return filterValue(white, white ? list : "", white ? "" : list);
        }
        String mode = value.substring(0, semi);
        if (!mode.equals(WHITELIST) && !mode.equals(BLACKLIST)) return null;
        int second = value.indexOf(';', semi + 1);
        if (second < 0 || value.indexOf(';', second + 1) >= 0) return null;
        if (apps(value.substring(semi + 1, second)) == null) return null;
        if (apps(value.substring(second + 1)) == null) return null;
        return value;
    }
    /** 应用过滤是否白名单；未设置或空值按白名单。 */
    public static boolean filterWhitelist(String value) {
        if (value == null || value.isEmpty()) return true;
        if (value.indexOf(';') >= 0) return value.startsWith(WHITELIST + ";");
        int colon = value.indexOf(':');
        return !BLACKLIST.equals(colon < 0 ? value : value.substring(0, colon));
    }
    /** 白名单自身的应用列表（与当前模式无关）。 */
    public static String filterWhiteList(String value) {
        if (value == null || value.isEmpty()) return "";
        int semi = value.indexOf(';');
        if (semi >= 0) {
            int second = value.indexOf(';', semi + 1);
            return second < 0 ? "" : value.substring(semi + 1, second);
        }
        int colon = value.indexOf(':');
        return filterWhitelist(value) && colon >= 0 ? value.substring(colon + 1) : "";
    }
    /** 黑名单自身的应用列表（与当前模式无关）。 */
    public static String filterBlackList(String value) {
        if (value == null || value.isEmpty()) return "";
        int semi = value.indexOf(';');
        if (semi >= 0) {
            int second = value.indexOf(';', semi + 1);
            return second < 0 ? "" : value.substring(second + 1);
        }
        int colon = value.indexOf(':');
        return !filterWhitelist(value) && colon >= 0 ? value.substring(colon + 1) : "";
    }
    /** 取当前模式使用的名单；白名单取白名单，黑名单取黑名单。 */
    public static String filterList(String value) {
        return filterWhitelist(value) ? filterWhiteList(value) : filterBlackList(value);
    }
    /** 生成应用过滤取值：模式 + 两份独立名单。 */
    public static String filterValue(boolean whitelist, String whiteList, String blackList) {
        return (whitelist ? WHITELIST : BLACKLIST) + ";"
                + (whiteList == null ? "" : whiteList) + ";"
                + (blackList == null ? "" : blackList);
    }
    /** 只切换模式，两份名单原样保留。 */
    public static String filterMode(String value, boolean whitelist) {
        return filterValue(whitelist, filterWhiteList(value), filterBlackList(value));
    }
    /** 只替换当前模式的名单，另一份原样保留。 */
    public static String withActiveList(String value, String list) {
        return filterWhitelist(value)
                ? filterValue(true, list, filterBlackList(value))
                : filterValue(false, filterWhiteList(value), list);
    }
    private static boolean packageName(String name) {
        if (name.isEmpty() || name.length() > 255 || name.charAt(0) == '.' || name.charAt(name.length() - 1) == '.') return false;
        char previous = '.';
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') { if (previous == '.') return false; }
            else if (c != '_' && !(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')) return false;
            previous = c;
        }
        return true;
    }
    /** 温区上界（不含），最后一段为 24°C 及以上；与屏幕波形表的 A2 温区一致。 */
    public static final int[] TEMPERATURE_BOUNDS = {3, 6, 9, 12, 15, 18, 21, 24};
    public static final int TEMPERATURE_BANDS = TEMPERATURE_BOUNDS.length + 1, CURVE_MAX_MS = 2000;

    /** 解析 curve: 取值；格式不对返回 null。只接受数字与逗号，不能夹带其他文本。 */
    public static int[] curve(String value) {
        if (value == null || !value.startsWith("curve:")) return null;
        String[] parts = value.substring(6).split(",", -1);
        if (parts.length != TEMPERATURE_BANDS) return null;
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,4}")) return null;
            result[i] = Integer.parseInt(parts[i]);
            if (result[i] > CURVE_MAX_MS) return null;
        }
        return result;
    }
    public static String curveValue(int[] ms) {
        if (ms.length != TEMPERATURE_BANDS) throw new IllegalArgumentException("温区数量不符");
        StringBuilder out = new StringBuilder("curve:");
        for (int i = 0; i < ms.length; i++) {
            if (ms[i] < 0 || ms[i] > CURVE_MAX_MS) throw new IllegalArgumentException("停留时长超出范围");
            out.append(i == 0 ? "" : ",").append(ms[i]);
        }
        return out.toString();
    }
    public static int band(double tempC) {
        for (int i = 0; i < TEMPERATURE_BOUNDS.length; i++) if (tempC < TEMPERATURE_BOUNDS[i]) return i;
        return TEMPERATURE_BOUNDS.length;
    }
}
