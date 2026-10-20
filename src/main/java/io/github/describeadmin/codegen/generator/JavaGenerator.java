package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.LinkedHashSet;
import java.util.List;
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
 *
 * <p>访问器与依赖注入构造器交给 Lombok（Entity 上的 {@code @Getter}/{@code @Setter}、
 * Controller 与 Service 上的 {@code @RequiredArgsConstructor}），与"薄代码"同向——生成物更小，
 * 没有逐字段的机械样板。Lombok 依赖由业务工程自带（archetype 已预置），
 * 框架自身源码不使用它。
 */
public final class JavaGenerator {

    private JavaGenerator() {
    }

    public static String entity(ModuleSpec s) {
        Set<String> imports = new LinkedHashSet<>();
        imports.add("com.baomidou.mybatisplus.annotation.TableName");
        imports.add("io.github.describeadmin.mybatis.api.BaseEntity");
        imports.add("lombok.Getter");
        imports.add("lombok.Setter");
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

        return """
                package %s;

                %s

                /**
                 * %s。
                 *
                 * <p>由 codegen 生成。审计字段（创建人/创建时间、更新人/更新时间、逻辑删除、乐观锁版本）
                 * 与主键均由 {@link BaseEntity} 承担，不要在此重复声明。
                 *
                 * <p>访问器由 Lombok 的 {@code @Getter} / {@code @Setter} 生成，不手写。
                 */
                @Getter
                @Setter
                @TableName("%s")
                public class %s extends BaseEntity {

                %s}
                """.formatted(
                s.packageOf("entity"),
                imports.stream().map(i -> "import " + i + ";").collect(Collectors.joining("\n")),
                s.comment(),
                s.table(),
                s.entityClass(),
                fieldDecls);
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
                import lombok.RequiredArgsConstructor;
                import org.springframework.stereotype.Service;

                /**
                 * %s Service。
                 *
                 * <p>由 codegen 生成。CRUD 与分页由 {@code BaseService} 提供，
                 * 业务特有逻辑写在这里；生成器不会覆盖本文件中手工添加的方法
                 * （重新生成时本文件默认被跳过，需覆盖请显式指定 --force）。
                 *
                 * <p>业务自身的依赖声明成 {@code private final} 字段即可，注入构造器由
                 * Lombok 的 {@code @RequiredArgsConstructor} 生成，不手写——与本类的
                 * Controller 一致。<b>本类对应的 Mapper 不在此列</b>：基类已经持有它
                 * （{@code getBaseMapper()}），再声明一个字段只是多一份等价引用。
                 */
                @Service
                @RequiredArgsConstructor
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
        List<FieldSpec> queryFields = s.queryFields();

        String queryDoc = queryFields.isEmpty()
                ? ""
                : queryFields.stream()
                .map(f -> " *   <li>{@code " + f.name() + "} —— " + f.comment()
                        + "（" + f.query().name().toLowerCase() + "）</li>")
                .collect(Collectors.joining("\n", "\n *\n * <p>列表查询支持的条件：\n * <ul>\n", "\n * </ul>"));

        // 统一收集后排序，而不是拼几段固定文本。查询参数的取值/类型转换
        // （text / asInt / asDate …）由 BaseController 提供，生成物只调用不声明，
        // 因此这里不再需要 BizException / ResultCode，也不需要按字段类型追加 import。
        FieldSpec labelField = s.optionLabelField();

        Set<String> imports = new java.util.TreeSet<>(List.of(
                "io.github.describeadmin.mybatis.api.BaseController",
                s.packageOf("entity") + "." + s.entityClass(),
                s.packageOf("mapper") + "." + s.mapperClass(),
                s.packageOf("service") + "." + s.serviceClass(),
                "lombok.RequiredArgsConstructor",
                "org.springframework.web.bind.annotation.RequestMapping",
                "org.springframework.web.bind.annotation.RestController"));
        if (!queryFields.isEmpty()) {
            imports.addAll(List.of(
                    "com.baomidou.mybatisplus.core.conditions.Wrapper",
                    "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper",
                    "java.util.Map"));
        }
        if (labelField != null) {
            imports.addAll(List.of(
                    "com.baomidou.mybatisplus.core.conditions.query.QueryWrapper",
                    "io.github.describeadmin.common.api.OptionItem",
                    "io.github.describeadmin.common.api.Result",
                    "java.util.List",
                    "org.springframework.util.StringUtils",
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.RequestParam"));
        }

        return """
                package %s;

                %s

                /**
                 * %s。
                 *
                 * <p>由 codegen 生成。继承 {@code BaseController} 即获得
                 * list / get / create / update / delete 五个标准端点，
                 * 业务特有接口在此追加。%s
                 */
                @RestController
                @RequestMapping("%s")
                @RequiredArgsConstructor
                public class %s extends BaseController<%s, %s, %s> {

                    private final %s %s;

                    @Override
                    protected %s getService() {
                        return %s;
                    }

                    /**
                     * 权限点前缀。
                     *
                     * <p>显式覆写，不依赖 {@code BaseController} 从 {@code @RequestMapping} 的推导：
                     * apiPrefix 默认会把模块名里的下划线换成连字符（{@code my_module} →
                     * {@code /api/my-module}），而菜单 SQL 登记的权限点用的是模块名原样
                     * （{@code my_module:list}）。靠推导会得到 {@code my-module:list}，
                     * 与授权数据对不上，表现为<b>连 ADMIN 都被 403</b>——
                     * 而错误信息里没有任何东西指向"权限点前缀拼错了"。
                     *
                     * <p>{@code public} 而非 {@code protected}：{@code BaseController.permPrefix()}
                     * 自 0.2.0 起是 {@code public}（供 {@code OperLogAspect} 跨包读取），
                     * 覆写不能收窄可见性。
                     */
                    @Override
                    public String permPrefix() {
                        return "%s";
                    }
                %s%s}
                """.formatted(
                s.packageOf("controller"),
                imports.stream().map(i -> "import " + i + ";").collect(Collectors.joining("\n")),
                s.comment(), queryDoc,
                s.apiPrefix(),
                s.controllerClass(), s.serviceClass(), s.mapperClass(), s.entityClass(),
                s.serviceClass(), s.entityVar() + "Service",
                s.serviceClass(), s.entityVar() + "Service",
                s.module(),
                labelField == null ? "" : optionsMethod(s, labelField),
                queryFields.isEmpty() ? "" : listMethod(s, queryFields));
    }

    /**
     * 生成 {@code GET <apiPrefix>/options}：本模块被别的模块引用时的选项数据源。
     *
     * <p><b>为什么生成这个端点</b>：业务表单里迟早要出现「选一个项目」「选一个客户」这类
     * 引用别的模块的下拉框。没有它，写页面的人只能去调对方的分页列表接口并硬编码一个
     * {@code size=200}——那是把分页接口当全量接口使，数据一多就静默丢，且调大参数也没用
     * （{@code PageQuery.MAX_SIZE} 是 500）。约定与分档见 CLAUDE.md 4.11。
     *
     * <p><b>业务实体落「受控引用」档，复用本模块的 {@code :list} 权限点</b>，
     * 与框架里 {@code /api/system/role/options} 那种「仅要求登录」的组织元数据不同：
     * <ul>
     *   <li>业务数据可能含敏感信息，枚举全部项目名称本身就是信息泄露；</li>
     *   <li>走 {@code getService()} 的正常查询链路，{@code DeptDataPermissionHandler}
     *       的行级过滤照常生效——换句话说「本部门」档的角色在下拉里也只看得到本部门的数据。
     *       这正是想要的，所以这里<b>不能</b>绕过 Service 直接查 Mapper。</li>
     * </ul>
     *
     * <p>上限与「超限抛错不截断」由 {@code BaseService.listOptionSource} 统一承担，
     * 生成物只提供 wrapper，保持"薄"。
     */
    private static String optionsMethod(ModuleSpec s, FieldSpec label) {
        return ("""

                    /**
                     * %s选项，供别的模块的下拉框使用。
                     *
                     * <p>不分页：下拉框少一项就是选不到。超过 {@code OptionItem.MAX_SIZE}
                     * 时抛错而非截断，请传 {@code keyword} 收窄。
                     *
                     * <p>权限点与列表一致（{@code %s:list}），数据权限照常生效。
                     */
                    @GetMapping("/options")
                    public Result<List<OptionItem>> options(@RequestParam(required = false) String keyword) {
                        requirePermission("list");
                        QueryWrapper<%s> wrapper = new QueryWrapper<%s>()
                                .select("id", "%s")
                                .likeRight(StringUtils.hasText(keyword), "%s", keyword)
                                .orderByDesc("id");
                        return Result.ok(%s.listOptionSource(wrapper, "%s").stream()
                                .map(e -> OptionItem.of(e.getId(), e.get%s()))
                                .toList());
                    }
                """).formatted(
                s.comment(), s.module(),
                s.entityClass(), s.entityClass(),
                label.column(), label.column(),
                s.entityVar() + "Service", s.comment(),
                Character.toUpperCase(label.name().charAt(0)) + label.name().substring(1));
    }

    /**
     * 覆写 list，把查询条件真正落到 SQL 上。
     *
     * <p><b>为什么必须生成这个方法</b>：{@code BaseController.list} 只接 {@code PageQuery}、
     * 不构造任何 Wrapper。若不覆写，spec 里的 {@code query: like/eq/range} 就只是一段注释，
     * 前端搜索栏点了没反应——「看起来能用、实际是死的」比没有这个功能更糟。
     *
     * <p>取值与类型转换（{@code text} / {@code asInt} / {@code asLong} / {@code asDecimal} /
     * {@code asDate} / {@code asDateTime}）由 {@code BaseController} 提供，这里只调用不声明——
     * 生成物因此保持"薄"，框架统一修正解析口径时业务方不必重跑生成器。
     */
    private static String listMethod(ModuleSpec s, List<FieldSpec> queryFields) {
        StringBuilder body = new StringBuilder();

        for (FieldSpec f : queryFields) {
            if (f.query() == FieldSpec.QueryMode.RANGE) {
                body.append(condition(f, f.name() + "Start", "ge"));
                body.append(condition(f, f.name() + "End", "le"));
            } else {
                body.append(condition(f, f.name(),
                        f.query() == FieldSpec.QueryMode.LIKE ? "likeRight" : "eq"));
            }
        }

        return """

                    /**
                     * 列表查询的筛选条件。
                     *
                     * <p>空值不参与筛选，否则「不填任何条件」会退化成 {@code WHERE col = ''}，一条都查不出。
                     * LIKE 一律右模糊，可走索引；不生成左模糊以免全表扫描。
                     *
                     * <p>{@code text} / {@code asInt} 等取参方法继承自 {@code BaseController}。
                     */
                    @Override
                    protected Wrapper<%s> buildListWrapper(Map<String, String> params) {
                        QueryWrapper<%s> wrapper = new QueryWrapper<>();
                %s        return wrapper;
                    }
                """.formatted(s.entityClass(), s.entityClass(), body);
    }

    /**
     * 单个筛选条件。非字符串类型经 {@code BaseController} 的 {@code asXxx} 转换，
     * 转换失败由基类统一返回 400。
     */
    private static String condition(FieldSpec f, String var, String op) {
        String key = "\"" + var + "\"";
        String expr = parserName(f.type()) + "(params, " + key + ")";
        return "        wrapper." + op + "(" + expr + " != null, \"" + f.column() + "\", "
                + expr + ");\n";
    }

    private static String parserName(FieldType type) {
        return switch (type) {
            case INT, FLAG -> "asInt";
            case LONG -> "asLong";
            case DECIMAL -> "asDecimal";
            case DATE -> "asDate";
            case DATETIME -> "asDateTime";
            case STRING, TEXT -> "text";
        };
    }
}
