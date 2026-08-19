package io.github.describeadmin.codegen.parser;

import java.util.List;

/**
 * Spec 校验失败。
 *
 * <p>刻意一次性收集<b>全部</b>错误再抛出，而不是遇到第一个就中断：
 * AI Agent（和人）修一轮就能改完所有问题，而不是"改一个跑一次"来回七八轮。
 */
public class SpecException extends RuntimeException {

    private final List<String> errors;

    public SpecException(List<String> errors) {
        super("Spec 校验失败，共 " + errors.size() + " 处问题：\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
