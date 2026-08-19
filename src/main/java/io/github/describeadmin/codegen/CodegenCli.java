package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.generator.DdlGenerator;
import io.github.describeadmin.codegen.generator.JavaGenerator;
import io.github.describeadmin.codegen.generator.TestSpecGenerator;
import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecException;
import io.github.describeadmin.codegen.parser.SpecLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。
 *
 * <pre>
 *   codegen &lt;spec.yaml&gt; [--out DIR] [--force] [--dry-run]
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>默认不覆盖已有文件</b>——Service 与 Controller 是业务方会手工添加逻辑的地方，
 *       重新生成时冲掉它们是不可接受的。需要覆盖必须显式 {@code --force}。</li>
 *   <li><b>{@code --dry-run} 只打印计划</b>，便于人和 AI 先确认影响面再落盘。</li>
 *   <li><b>输出全部走 UTF-8</b>，不受平台默认编码影响（CLAUDE.md 3.6）。</li>
 * </ul>
 */
public final class CodegenCli {

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            System.exit(run(args, out));
        } catch (SpecException e) {
            out.println("✗ " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            out.println("✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args, PrintStream out) throws IOException {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }

        Path spec = Path.of(args[0]);
        Path outDir = Path.of(".");
        boolean force = false;
        boolean dryRun = false;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = Path.of(args[++i]);
                case "--force" -> force = true;
                case "--dry-run" -> dryRun = true;
                default -> {
                    out.println("✗ 未知参数: " + args[i]);
                    printUsage(out);
                    return 1;
                }
            }
        }

        if (!Files.exists(spec)) {
            out.println("✗ Spec 文件不存在: " + spec.toAbsolutePath());
            return 1;
        }

        ModuleSpec s = SpecLoader.load(spec);
        Map<Path, String> files = plan(s, outDir);

        out.println("模块: " + s.comment() + " (" + s.module() + ")");
        out.println("表名: " + s.table() + "    字段: " + s.fields().size()
                + "    接口前缀: " + s.apiPrefix());
        out.println();

        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<Path, String> e : files.entrySet()) {
            Path target = e.getKey();
            boolean exists = Files.exists(target);
            String rel = outDir.relativize(target).toString().replace('\\', '/');

            if (exists && !force) {
                out.println("  跳过  " + rel + "   (已存在，用 --force 覆盖)");
                skipped.add(rel);
                continue;
            }
            if (dryRun) {
                out.println("  将写  " + rel + (exists ? "   (覆盖)" : ""));
                written.add(rel);
                continue;
            }
            Files.createDirectories(target.getParent());
            // 显式 UTF-8：生成物含中文注释，平台默认编码会写坏
            Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            out.println("  " + (exists ? "覆盖" : "生成") + "  " + rel);
            written.add(rel);
        }

        out.println();
        out.printf("完成：%s %d 个文件，跳过 %d 个%n",
                dryRun ? "将写入" : "写入", written.size(), skipped.size());
        if (!skipped.isEmpty() && !dryRun) {
            out.println("提示：Service / Controller 常含手工添加的业务逻辑，默认不覆盖是有意为之。");
        }
        return 0;
    }

    /** 计算待生成的文件清单。抽出来便于测试与 --dry-run 复用。 */
    static Map<Path, String> plan(ModuleSpec s, Path outDir) {
        Path javaRoot = outDir.resolve("src/main/java");
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(javaRoot.resolve(pkgPath(s.packageOf("entity"))).resolve(s.entityClass() + ".java"),
                JavaGenerator.entity(s));
        files.put(javaRoot.resolve(pkgPath(s.packageOf("mapper"))).resolve(s.mapperClass() + ".java"),
                JavaGenerator.mapper(s));
        files.put(javaRoot.resolve(pkgPath(s.packageOf("service"))).resolve(s.serviceClass() + ".java"),
                JavaGenerator.service(s));
        files.put(javaRoot.resolve(pkgPath(s.packageOf("controller"))).resolve(s.controllerClass() + ".java"),
                JavaGenerator.controller(s));
        files.put(outDir.resolve("src/main/resources/db").resolve("schema-" + s.table() + ".sql"),
                DdlGenerator.generate(s));
        files.put(outDir.resolve("test-specs").resolve(s.module() + ".yaml"),
                TestSpecGenerator.generate(s));
        return files;
    }

    private static String pkgPath(String pkg) {
        return pkg.replace('.', '/');
    }

    private static void printUsage(PrintStream out) {
        out.println("""
                describeadmin codegen —— 以 YAML 为输入生成薄业务代码

                用法:
                  codegen <spec.yaml> [选项]

                选项:
                  --out DIR    输出根目录（默认当前目录）
                  --force      覆盖已存在的文件（默认跳过）
                  --dry-run    只打印将要生成的文件，不写盘
                  -h, --help   显示本帮助

                产出:
                  src/main/java/<pkg>/<module>/entity/<Entity>Entity.java
                  src/main/java/<pkg>/<module>/mapper/<Entity>Mapper.java
                  src/main/java/<pkg>/<module>/service/<Entity>Service.java
                  src/main/java/<pkg>/<module>/controller/<Entity>Controller.java
                  src/main/resources/db/schema-<table>.sql
                  test-specs/<module>.yaml          结构化端到端验收用例

                Spec 示例见 examples/ 目录；字段类型与校验规则见 README.md。""");
    }
}
