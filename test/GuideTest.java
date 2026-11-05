import com.inkcontrol.core.*;
import java.lang.reflect.*;
import java.util.*;

/** 编译时只依赖核心；运行时另将 Guide.java 编入类路径，无需 Android 环境。 */
public final class GuideTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("guide check " + checks); }
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static Object call(Class<?> type, String name, String id) throws Exception {
        Method m = type.getDeclaredMethod(name, String.class); m.setAccessible(true); return m.invoke(null, id);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.inkcontrol.ui.Guide");
        Field categoriesField = type.getDeclaredField("CATEGORIES"); categoriesField.setAccessible(true);
        Map<?, ?> categories = (Map<?, ?>) categoriesField.get(null);
        Field internalField = type.getDeclaredField("INTERNAL"); internalField.setAccessible(true);
        Set<String> INTERNAL = (Set<String>) internalField.get(null);
        Set<String> navigation = new HashSet<>();
        // 收在子页里、不在分类列表出现的功能（「显示信息修改」页里的四项显示开关）
        Set<String> nested = new HashSet<>();
        for (Object category : categories.values()) {
            for (String[] section : (String[][]) field(category, "sections")) {
                for (int i = 1; i < section.length; i++) {
                    check(navigation.add(section[i])); FeatureCatalog.get(section[i]);
                }
            }
            for (String id : (String[]) field(category, "nested")) {
                check(!navigation.contains(id)); nested.add(id); FeatureCatalog.get(id);
            }
        }
        check(nested.containsAll(Arrays.asList("lock.ext.wifi", "lock.ext.bt", "lock.ext.audio", "lock.ext.airplane")));
        // 面板长按「自动刷新 / 遮挡息屏」拿 FeatureCatalog 的 group 当分类名跳大功能页。
        // MainActivity 只放行真实存在的分类；这两个名字一旦改了而没同步 Guide，长按就会静默失效。
        check(categories.containsKey(FeatureCatalog.get("refresh.master").group));
        check(categories.containsKey(FeatureCatalog.get("wave.lock").group));
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            Object entry = call(type, "get", feature.id);
            check(!field(entry, "summary").toString().isEmpty());
            check(!field(entry, "what").toString().isEmpty());
            Map<?, ?> options = (Map<?, ?>) field(entry, "options");
            // 纯占位取值（@path / @font）的功能是页内选择器，不写死选项说明
            boolean tokensOnly = true;
            for (String value : feature.values) if (!value.startsWith("@")) tokensOnly = false;
            if (!tokensOnly && !options.keySet().equals(new HashSet<>(feature.values)))
                throw new AssertionError(feature.id + " guide options " + options.keySet() + " != " + feature.values);
            checks++;
            for (Object option : options.values()) check(!field(option, "name").toString().isEmpty());
            for (String[] term : (List<String[]>) field(entry, "terms")) check(term[1] != null && !term[1].isEmpty());
            String destination = (String) call(type, "destination", feature.id);
            // 只有数据/页内操作的功能没有独立入口，不要求出现在分类小节里
            if (!INTERNAL.contains(feature.id) && !nested.contains(feature.id)) check(navigation.contains(destination));
        }
        check(!navigation.contains("experiment.video"));
        for (String scope : DisplayAlgorithms.SCOPES) {
            check(navigation.contains("display.alg." + scope));
            check(!navigation.contains("display.hyst." + scope));
        }
        check(call(type, "destination", "experiment.video").equals("display.alg.video"));
        check(call(type, "destination", "display.hyst.fast").equals("display.alg.fast"));
        Object video = call(type, "get", "display.alg.video");
        Object stock = ((Map<?, ?>) field(video, "options")).get("stock");
        check(field(stock, "detail").toString().contains("不支持独立迟滞"));
        Method status = type.getDeclaredMethod("selectionStatus", boolean.class, String.class); status.setAccessible(true);
        check(status.invoke(null, false, "关闭").equals("沿用当前默认"));
        check(status.invoke(null, false, "已生效").equals("沿用当前默认"));
        for (String receipt : Arrays.asList("等待运行确认", "等待停用确认", "待重启", "异常：测试"))
            check(status.invoke(null, false, receipt).equals(receipt));
        check(status.invoke(null, true, "关闭").equals("关闭"));
        System.out.println("guide checks passed: " + checks);
    }
}
