package io.github.describeadmin.codegen.parser;

import io.github.describeadmin.codegen.model.ModuleSpec;

import java.util.List;

/** 与 SpecLoader 同包，用于在测试中访问其包私有的方法。 */
public final class SpecLoaderBridge {

    private SpecLoaderBridge() {
    }

    public static ModuleSpec parse(Object raw, String source) {
        return SpecLoader.parse(raw, source);
    }

    public static ModuleSpec parse(Object raw, String source, String layoutOverride) {
        return SpecLoader.parse(raw, source, layoutOverride);
    }

    /** 直接测布局解析：环境变量在单测里改不动，只能把值当参数喂进来。 */
    public static SpecLoader.LayoutChoice resolveLayout(String cliOverride, Object specValue,
                                                        String envValue, List<String> errors) {
        return SpecLoader.resolveLayout(cliOverride, specValue, envValue, errors);
    }
}
