package io.github.describeadmin.codegen.parser;

import io.github.describeadmin.codegen.model.ModuleSpec;

/** 与 SpecLoader 同包，用于在测试中访问其包私有的 parse 方法。 */
public final class SpecLoaderBridge {

    private SpecLoaderBridge() {
    }

    public static ModuleSpec parse(Object raw, String source) {
        return SpecLoader.parse(raw, source);
    }
}
