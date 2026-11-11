import com.inkcontrol.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class CatalogExport {
    public static void main(String[] args) throws Exception {
        StringBuilder schema = new StringBuilder();
        for (FeatureDefinition feature : FeatureCatalog.all()) schema.append(feature.id).append('\t').append(String.join(",", feature.values)).append('\t').append(feature.dependency).append('\n');
        Files.write(Paths.get(args[0], "features.tsv"), schema.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(args[0], "retired.txt"), (String.join("\n", FeatureCatalog.RETIRED) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(args[0], "defaults.txt"), new Configuration().text().getBytes(StandardCharsets.UTF_8));
    }
}
