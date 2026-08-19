package io.github.describeadmin.codegen.parser;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 YAML 解析为 {@link ModuleSpec} 并做完整校验。
 *
 * <p>校验的设计原则：**一次性报出全部问题**，且每条错误都指明位置与修法。
 * 生成器是给 AI Agent 用的主要接口之一，"错误信息可操作"直接决定它能不能自主用好。
 */
public final class SpecLoader {

    /** BaseEntity 已提供的字段，业务 spec 里重复声明会导致列冲突。 */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "id", "createBy", "createTime", "updateBy", "updateTime", "deleted", "version");
    private static final Set<String> RESERVED_COLUMNS = Set.of(
            "id", "create_by", "create_time", "update_by", "update_time", "deleted", "version");

    private static final Pattern LOWER_CAMEL = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    private static final Pattern UPPER_CAMEL = Pattern.compile("^[A-Z][A-Za-z0-9]*$");
    private static final Pattern SNAKE = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern PACKAGE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    /** MySQL 5.7 单列索引键长度上限 767 字节；utf8mb4 每字符 4 字节 → 191 字符。 */
    private static final int MAX_INDEXED_VARCHAR = 191;

    private SpecLoader() {
    }

    public static ModuleSpec load(Path file) throws IOException {
        // 显式指定 UTF-8：默认平台编码会在中文 Windows 上把注释读成乱码（CLAUDE.md 3.6）
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(new Yaml().load(r), file.getFileName().toString());
        }
    }

    @SuppressWarnings("unchecked")
    static ModuleSpec parse(Object raw, String source) {
        List<String> errors = new ArrayList<>();
        if (!(raw instanceof Map)) {
            throw new SpecException(List.of(source + ": 根节点必须是对象（key: value 形式）"));
        }
        Map<String, Object> root = (Map<String, Object>) raw;

        String basePackage = str(root, "basePackage");
        String module = str(root, "module");
        String entity = str(root, "entity");
        String table = str(root, "table");
        String comment = str(root, "comment");
        String apiPrefix = str(root, "apiPrefix");

        require(errors, basePackage, "basePackage", "业务方基础包名，如 com.example.demo");
        require(errors, module, "module", "模块名（小写），如 project");
        require(errors, entity, "entity", "实体名（大驼峰，不带 Entity 后缀），如 Project");
        require(errors, table, "table", "表名（下划线），如 biz_project");

        if (basePackage != null && !PACKAGE.matcher(basePackage).matches()) {
            errors.add("basePackage \"" + basePackage + "\" 不是合法包名；应形如 com.example.demo，全小写、点分隔");
        }
        if (module != null && !SNAKE.matcher(module).matches()) {
            errors.add("module \"" + module + "\" 应为小写字母开头的小写/下划线形式，如 project 或 work_order");
        }
        if (entity != null && !UPPER_CAMEL.matcher(entity).matches()) {
            errors.add("entity \"" + entity + "\" 应为大驼峰且不带 Entity 后缀，如 Project");
        }
        if (entity != null && entity.endsWith("Entity")) {
            errors.add("entity \"" + entity + "\" 不要带 Entity 后缀，生成器会自动加，否则会产出 " + entity + "Entity");
        }
        if (table != null && !SNAKE.matcher(table).matches()) {
            errors.add("table \"" + table + "\" 应为小写下划线形式，如 biz_project");
        }
        if (table != null && table.startsWith("sys_")) {
            errors.add("table \"" + table + "\" 使用了 sys_ 前缀，该前缀属于 framework-system-starter 的系统管理表；"
                    + "业务表请用 biz_ 或业务自有前缀");
        }

        List<FieldSpec> fields = parseFields(root.get("fields"), errors);
        if (fields.isEmpty() && errors.isEmpty()) {
            errors.add("fields 为空：至少需要一个业务字段（审计字段由 BaseEntity 提供，不要在此声明）");
        }

        if (!errors.isEmpty()) {
            throw new SpecException(errors.stream().map(e -> source + ": " + e).toList());
        }

        return new ModuleSpec(basePackage, module, entity, table,
                comment == null ? entity : comment,
                apiPrefix == null ? "/api/" + module.replace('_', '-') : apiPrefix,
                fields);
    }

    @SuppressWarnings("unchecked")
    private static List<FieldSpec> parseFields(Object raw, List<String> errors) {
        if (raw == null) {
            errors.add("缺少 fields");
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            errors.add("fields 必须是数组");
            return List.of();
        }

        List<FieldSpec> result = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenColumns = new HashSet<>();

        for (int i = 0; i < list.size(); i++) {
            String at = "fields[" + i + "]";
            if (!(list.get(i) instanceof Map)) {
                errors.add(at + " 必须是对象");
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) list.get(i);

            String name = str(m, "name");
            if (name == null) {
                errors.add(at + " 缺少 name");
                continue;
            }
            at = "字段 " + name;

            if (!LOWER_CAMEL.matcher(name).matches()) {
                errors.add(at + ": name 应为小驼峰，如 projectName");
            }
            if (RESERVED_FIELDS.contains(name)) {
                errors.add(at + ": 与 BaseEntity 的内置字段重名。审计字段（创建人/时间、更新人/时间、"
                        + "逻辑删除、乐观锁版本）与主键均由 BaseEntity 提供，不要重复声明");
            }
            if (!seenNames.add(name)) {
                errors.add(at + ": name 重复");
            }

            String column = str(m, "column");
            if (column == null) {
                column = toSnake(name);
            } else if (!SNAKE.matcher(column).matches()) {
                errors.add(at + ": column \"" + column + "\" 应为小写下划线形式");
            }
            if (RESERVED_COLUMNS.contains(column)) {
                errors.add(at + ": column \"" + column + "\" 与 BaseEntity 的内置列冲突");
            }
            if (!seenColumns.add(column)) {
                errors.add(at + ": column \"" + column + "\" 重复");
            }

            String typeKey = str(m, "type");
            FieldType type = null;
            if (typeKey == null) {
                errors.add(at + ": 缺少 type，可选值 " + FieldType.allKeys());
            } else {
                type = FieldType.of(typeKey).orElse(null);
                if (type == null) {
                    errors.add(at + ": 未知类型 \"" + typeKey + "\"，可选值 " + FieldType.allKeys()
                            + hintForRejectedType(typeKey));
                }
            }

            Integer length = intOf(m, "length");
            Integer precision = intOf(m, "precision");
            Integer scale = intOf(m, "scale");
            boolean nullable = boolOf(m, "nullable", true);
            boolean indexed = boolOf(m, "indexed", false);
            String comment = str(m, "comment");

            FieldSpec.QueryMode query = FieldSpec.QueryMode.NONE;
            String queryKey = str(m, "query");
            if (queryKey != null) {
                try {
                    query = FieldSpec.QueryMode.valueOf(queryKey.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add(at + ": 未知 query 取值 \"" + queryKey + "\"，可选 none / eq / like / range");
                }
            }

            if (type == FieldType.STRING && indexed && length != null && length > MAX_INDEXED_VARCHAR) {
                errors.add(at + ": VARCHAR(" + length + ") 建索引会超出 MySQL 5.7 的 767 字节键长上限"
                        + "（utf8mb4 下最多 " + MAX_INDEXED_VARCHAR + " 字符）。"
                        + "请把 length 降到 " + MAX_INDEXED_VARCHAR + " 以内，或去掉 indexed");
            }
            if (type == FieldType.TEXT && indexed) {
                errors.add(at + ": TEXT 列不能直接建索引（需要前缀索引），请改用 string 类型或去掉 indexed");
            }
            if (query == FieldSpec.QueryMode.LIKE && type != FieldType.STRING && type != FieldType.TEXT) {
                errors.add(at + ": query: like 仅适用于 string / text 类型");
            }

            if (type != null) {
                result.add(new FieldSpec(name, column, type, length, precision, scale,
                        nullable, comment == null ? name : comment, query, indexed));
            }
        }
        return result;
    }

    /** 对被刻意排除的类型给出替代方案，而不是简单说"未知类型"。 */
    private static String hintForRejectedType(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "timestamp" -> "。提示：本项目不使用 TIMESTAMP（2038 上限，且自动更新语义各库不一致），请改用 datetime";
            case "boolean", "bool" -> "。提示：请改用 flag（对应 TINYINT，0/1 语义）";
            case "json" -> "。提示：JSON 函数集在 5.7 与各国产化库上差异大，请改用 text 并在应用层序列化";
            case "float", "double" -> "。提示：金额等场景请用 decimal 避免精度丢失";
            default -> "";
        };
    }

    static String toSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void require(List<String> errors, String value, String key, String hint) {
        if (value == null || value.isBlank()) {
            errors.add("缺少必填项 " + key + "（" + hint + "）");
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Integer intOf(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    private static boolean boolOf(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }
}
