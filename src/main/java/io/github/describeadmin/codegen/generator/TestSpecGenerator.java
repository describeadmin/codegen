package io.github.describeadmin.codegen.generator;

import io.github.describeadmin.codegen.model.FieldSpec;
import io.github.describeadmin.codegen.model.FieldType;
import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成结构化测试 Spec（develop_plan.md 5.4 格式）。
 *
 * <p><b>这是生成器最有价值的产出之一</b>：代码与它的验收用例一起生成，而不是等人补测试。
 * AI Agent 拿到生成的 Spec 就能直接执行端到端验证，把目标 #2（面向 AI 编程）
 * 与目标 #3（AI 自主端到端测试）连起来。
 *
 * <p>断言刻意<b>同时包含 UI 与 DB 两侧</b>，且 DB 断言比对具体值而非行数——
 * 只比 {@code COUNT(*)} 查不出字符集损坏这类问题（CLAUDE.md 3.6）。
 *
 * <p><b>实现注意</b>：本类不使用 Java 文本块拼装 YAML。文本块会剥离公共缩进，
 * 而 YAML 对缩进敏感，二者叠加极易产出结构错乱、却又"看起来没问题"的文件。
 * 这里用显式字符串拼接，缩进由代码直接控制。
 */
public final class TestSpecGenerator {

    private static final String NL = "\n";

    private TestSpecGenerator() {
    }

    public static String generate(ModuleSpec s) {
        FieldSpec primary = s.fields().get(0);
        String uiValue = displayValue(primary);
        String sqlValue = sqlLiteral(primary);
        String route = s.apiPrefix().replaceFirst("^/api", "");
        String m = s.module();
        String t = s.table();
        String col = primary.column();

        List<FieldSpec> formFields = s.fields().stream()
                .filter(f -> f.type() != FieldType.TEXT)
                .limit(3)
                .toList();

        String fillSteps = formFields.stream()
                .map(f -> "  - action: fill" + NL
                        + "    selector: '[data-testid=\"" + m + "-" + kebab(f.name()) + "-input\"]'" + NL
                        + "    value: " + displayValue(f))
                .collect(Collectors.joining(NL));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(s.comment()).append(" —— 端到端验收用例").append(NL);
        sb.append("#").append(NL);
        sb.append("# 由 codegen 生成。执行方式见 develop_plan.md 第五章。").append(NL);
        sb.append("#").append(NL);
        sb.append("# 约定：所有交互元素必须带 data-testid，命名 <模块>-<对象>-<动作>（CLAUDE.md 4.4）。").append(NL);
        sb.append("# 没有 data-testid 的元素视为未完成，本用例会定位失败。").append(NL);
        sb.append(NL);

        // ---- 场景一：新增 ----
        sb.append("scenario: 新增").append(s.comment()).append("后在列表中可见").append(NL);
        sb.append("preconditions:").append(NL);
        sb.append("  - login_as: admin").append(NL);
        sb.append("steps:").append(NL);
        sb.append("  - action: navigate").append(NL);
        sb.append("    target: ").append(route).append(NL);
        sb.append("  - action: click").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-add-btn\"]'").append(NL);
        sb.append(fillSteps).append(NL);
        sb.append("  - action: click").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-submit-btn\"]'").append(NL);
        sb.append("assertions:").append(NL);
        sb.append("  # UI 与 DB 双重校验：仅凭页面看起来正常不足以下结论（develop_plan.md 5.4）").append(NL);
        sb.append("  - type: ui").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-table\"]'").append(NL);
        sb.append("    expect: contains_text(").append(uiValue).append(")").append(NL);
        sb.append("  - type: db").append(NL);
        sb.append("    # 比对具体值而非 COUNT(*) —— 字符集损坏时计数依然正确（CLAUDE.md 3.6）").append(NL);
        // SQL 用单引号字面量，整条 query 用单引号 YAML 标量并对内部单引号做 '' 转义
        sb.append("    query: ").append(yamlSingleQuoted(
                "SELECT " + col + " FROM " + t + " WHERE " + col + " = " + sqlValue + " AND deleted = 0")).append(NL);
        sb.append("    expect: equals(").append(uiValue).append(")").append(NL);
        sb.append("  - type: db").append(NL);
        sb.append("    # 审计字段应由框架自动填充，业务代码不赋值").append(NL);
        sb.append("    query: ").append(yamlSingleQuoted(
                "SELECT COUNT(*) FROM " + t + " WHERE " + col + " = " + sqlValue
                        + " AND create_time IS NOT NULL AND deleted = 0")).append(NL);
        sb.append("    expect: equals(1)").append(NL);
        sb.append(evidence());

        // ---- 场景二：逻辑删除 ----
        sb.append(NL).append("---").append(NL);
        sb.append("scenario: 删除").append(s.comment()).append("后列表中不再可见且物理行保留").append(NL);
        sb.append("preconditions:").append(NL);
        sb.append("  - login_as: admin").append(NL);
        sb.append("  - db_seed: ").append(yamlSingleQuoted(
                "INSERT INTO " + t + " (" + col + ", create_time, update_time, deleted, version) "
                        + "VALUES (" + sqlValue + ", NOW(), NOW(), 0, 0)")).append(NL);
        sb.append("steps:").append(NL);
        sb.append("  - action: navigate").append(NL);
        sb.append("    target: ").append(route).append(NL);
        sb.append("  - action: click").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-delete-btn\"]'").append(NL);
        sb.append("  - action: click").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-confirm-btn\"]'").append(NL);
        sb.append("assertions:").append(NL);
        sb.append("  - type: ui").append(NL);
        sb.append("    selector: '[data-testid=\"").append(m).append("-table\"]'").append(NL);
        sb.append("    expect: not_contains_text(").append(uiValue).append(")").append(NL);
        sb.append("  - type: db").append(NL);
        sb.append("    # 逻辑删除：列表查询过滤掉，但物理行必须仍在").append(NL);
        sb.append("    query: ").append(yamlSingleQuoted(
                "SELECT deleted FROM " + t + " WHERE " + col + " = " + sqlValue)).append(NL);
        sb.append("    expect: equals(1)").append(NL);
        sb.append(evidence());

        return sb.toString();
    }

    private static String evidence() {
        return "evidence:" + NL
                + "  - screenshot: after_each_step" + NL
                + "  - console_log: on_error" + NL
                + "  - network_log: on_error" + NL;
    }

    /** 展示用取值，出现在 contains_text(...) / value: 中。 */
    private static String displayValue(FieldSpec f) {
        return switch (f.type()) {
            case STRING, TEXT -> "\"测试" + f.comment() + "-自动化\"";
            case INT, LONG, FLAG -> "1";
            case DECIMAL -> "1000.00";
            case DATE -> "\"2026-01-01\"";
            case DATETIME -> "\"2026-01-01 10:00:00\"";
        };
    }

    /** SQL 字面量：字符串用单引号，数字裸写。 */
    private static String sqlLiteral(FieldSpec f) {
        return switch (f.type()) {
            case STRING, TEXT -> "'测试" + f.comment() + "-自动化'";
            case INT, LONG, FLAG -> "1";
            case DECIMAL -> "1000.00";
            case DATE -> "'2026-01-01'";
            case DATETIME -> "'2026-01-01 10:00:00'";
        };
    }

    /** 包成 YAML 单引号标量，内部单引号按 YAML 规则双写转义。 */
    private static String yamlSingleQuoted(String raw) {
        return "'" + raw.replace("'", "''") + "'";
    }

    private static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
