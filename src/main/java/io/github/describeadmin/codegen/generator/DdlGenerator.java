package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成建表 DDL。
 *
 * <p>产出严格遵守 CLAUDE.md 3.1 的 SQL 红线，且这些约束由类型系统与本类共同保证，
 * 不依赖使用者记得——生成器<b>产不出</b>违规 SQL：
 * <ul>
 *   <li>时间列一律 {@code DATETIME}，不用 {@code TIMESTAMP}（类型系统层面已排除）</li>
 *   <li>显式声明 {@code CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci}，不依赖服务器默认值</li>
 *   <li>不生成函数索引、生成列、CHECK 约束</li>
 *   <li>索引键长度受 {@code SpecLoader} 校验约束在 5.7 上限内</li>
 *   <li>审计字段与主键固定生成，与 {@code BaseEntity} 一一对应</li>
 * </ul>
 */
public final class DdlGenerator {

    private DdlGenerator() {
    }

    public static String generate(ModuleSpec s) {
        List<String> columns = new ArrayList<>();
        columns.add(pad("id") + " BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键'");

        for (FieldSpec f : s.fields()) {
            columns.add("%s %s %s COMMENT '%s'".formatted(
                    pad(f.column()),
                    padType(f.sqlColumnType()),
                    f.nullable() ? "    NULL" : "NOT NULL",
                    escape(f.comment())));
        }

        // 审计字段与 BaseEntity 一一对应，顺序固定，便于人工比对
        columns.add(pad("create_by") + " BIGINT          NULL COMMENT '创建人'");
        columns.add(pad("create_time") + " DATETIME        NULL COMMENT '创建时间'");
        columns.add(pad("update_by") + " BIGINT          NULL COMMENT '更新人'");
        columns.add(pad("update_time") + " DATETIME        NULL COMMENT '更新时间'");
        columns.add(pad("deleted") + " TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'");
        columns.add(pad("version") + " INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'");

        List<String> keys = new ArrayList<>();
        keys.add("PRIMARY KEY (id)");
        for (FieldSpec f : s.fields()) {
            if (f.indexed()) {
                keys.add("KEY idx_%s_%s (%s)".formatted(s.table(), f.column(), f.column()));
            }
        }

        String body = java.util.stream.Stream.concat(columns.stream(), keys.stream())
                .collect(Collectors.joining(",\n  "));

        return """
                -- %s
                --
                -- 由 codegen 生成。语法基线：MySQL 5.7 安全子集（CLAUDE.md 3.1）
                -- 审计字段与 BaseEntity 一一对应，请勿手工增删。
                CREATE TABLE IF NOT EXISTS %s (
                  %s
                ) ENGINE=InnoDB
                  DEFAULT CHARACTER SET utf8mb4
                  COLLATE utf8mb4_general_ci
                  COMMENT='%s';
                """.formatted(s.comment(), s.table(), body, escape(s.comment()));
    }

    private static String pad(String col) {
        return String.format("%-14s", col);
    }

    private static String padType(String type) {
        return String.format("%-11s", type);
    }

    /** DDL 里的注释是单引号字符串，注释文本中的单引号必须转义。 */
    private static String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
