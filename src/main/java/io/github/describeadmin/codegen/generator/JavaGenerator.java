package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生成 Entity / Mapper / Service / Controller 四件套。
 *
 * <p>用 Java 文本块而非模板引擎：零依赖、产出完全确定，且模板本身就是可读的目标代码——
 * 改模板时看到的就是生成出来的样子，不需要在模板语法和结果之间做心智转换。
 *
 * <p><b>生成的是"薄"代码</b>：CRUD、分页、审计字段、逻辑删除、乐观锁全部由框架基类承担，
 * 生成物里只有业务自己的字段与方法。框架升级时改的是基类，这些薄代码基本不用跟着动——
 * 这是 develop_plan.md 目标 #5（可持续升级）在生成器侧的落点。
 */
public final class JavaGenerator {

    private JavaGenerator() {
    }

    public static String entity(ModuleSpec s) {
        Set<String> imports = new LinkedHashSet<>();
        imports.add("com.baomidou.mybatisplus.annotation.TableName");
        imports.add("io.github.describeadmin.mybatis.api.BaseEntity");
        for (FieldSpec f : s.fields()) {
            if (f.type().importName() != null) {
                imports.add(f.type().importName());
            }
        }

        String fieldDecls = s.fields().stream()
                .map(f -> """
                            /** %s */
                            private %s %s;
                        """.formatted(f.comment(), f.type().javaSimpleType(), f.name()))
                .collect(Collectors.joining("\n"));

        String accessors = s.fields().stream()
                .map(f -> """
                            public %s get%s() {
                                return %s;
                            }

                            public void set%s(%s %s) {
                                this.%s = %s;
                            }
                        """.formatted(
                        f.type().javaSimpleType(), f.capitalized(), f.name(),
                        f.capitalized(), f.type().javaSimpleType(), f.name(),
                        f.name(), f.name()))
                .collect(Collectors.joining("\n"));

        return """
                package %s;

                %s

                /**
                 * %s。
                 *
                 * <p>由 codegen 生成。审计字段（创建人/创建时间、更新人/更新时间、逻辑删除、乐观锁版本）
                 * 与主键均由 {@link BaseEntity} 承担，不要在此重复声明。
                 */
                @TableName("%s")
                public class %s extends BaseEntity {

                %s
                %s}
                """.formatted(
                s.packageOf("entity"),
                imports.stream().map(i -> "import " + i + ";").collect(Collectors.joining("\n")),
                s.comment(),
                s.table(),
                s.entityClass(),
                fieldDecls,
                accessors);
    }

    public static String mapper(ModuleSpec s) {
        return """
                package %s;

                import com.baomidou.mybatisplus.core.mapper.BaseMapper;
                import %s.%s;

                /**
                 * %s Mapper。
                 *
                 * <p>由 codegen 生成。通用 CRUD 由 {@code BaseMapper} 提供，
                 * 需要自定义 SQL 时在此追加方法——注意遵守 CLAUDE.md 3.1 的 SQL 红线。
                 */
                public interface %s extends BaseMapper<%s> {
                }
                """.formatted(
                s.packageOf("mapper"),
                s.packageOf("entity"), s.entityClass(),
                s.comment(),
                s.mapperClass(), s.entityClass());
    }

    public static String service(ModuleSpec s) {
        return """
                package %s;

                import io.github.describeadmin.mybatis.api.BaseService;
                import %s.%s;
                import %s.%s;
                import org.springframework.stereotype.Service;

                /**
                 * %s Service。
                 *
                 * <p>由 codegen 生成。CRUD 与分页由 {@code BaseService} 提供，
                 * 业务特有逻辑写在这里；生成器不会覆盖本文件中手工添加的方法
                 * （重新生成时本文件默认被跳过，需覆盖请显式指定 --force）。
                 */
                @Service
                public class %s extends BaseService<%s, %s> {
                }
                """.formatted(
                s.packageOf("service"),
                s.packageOf("entity"), s.entityClass(),
                s.packageOf("mapper"), s.mapperClass(),
                s.comment(),
                s.serviceClass(), s.mapperClass(), s.entityClass());
    }

    public static String controller(ModuleSpec s) {
        String queryDoc = s.queryFields().isEmpty()
                ? ""
                : s.queryFields().stream()
                .map(f -> " *   <li>{@code " + f.name() + "} —— " + f.comment()
                        + "（" + f.query().name().toLowerCase() + "）</li>")
                .collect(Collectors.joining("\n", "\n *\n * <p>列表查询支持的条件：\n * <ul>\n", "\n * </ul>"));

        return """
                package %s;

                import io.github.describeadmin.mybatis.api.BaseController;
                import %s.%s;
                import %s.%s;
                import %s.%s;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                /**
                 * %s。
                 *
                 * <p>由 codegen 生成。继承 {@code BaseController} 即获得
                 * list / get / create / update / delete 五个标准端点，
                 * 业务特有接口在此追加。%s
                 */
                @RestController
                @RequestMapping("%s")
                public class %s extends BaseController<%s, %s, %s> {

                    private final %s %s;

                    public %s(%s %s) {
                        this.%s = %s;
                    }

                    @Override
                    protected %s getService() {
                        return %s;
                    }
                }
                """.formatted(
                s.packageOf("controller"),
                s.packageOf("entity"), s.entityClass(),
                s.packageOf("mapper"), s.mapperClass(),
                s.packageOf("service"), s.serviceClass(),
                s.comment(), queryDoc,
                s.apiPrefix(),
                s.controllerClass(), s.serviceClass(), s.mapperClass(), s.entityClass(),
                s.serviceClass(), s.entityVar() + "Service",
                s.controllerClass(), s.serviceClass(), s.entityVar() + "Service",
                s.entityVar() + "Service", s.entityVar() + "Service",
                s.serviceClass(), s.entityVar() + "Service");
    }
}
