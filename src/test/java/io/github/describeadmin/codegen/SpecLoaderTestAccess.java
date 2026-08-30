package io.github.describeadmin.codegen;

import io.github.describeadmin.codegen.model.ModuleSpec;
import io.github.describeadmin.codegen.parser.SpecLoader;
import io.github.describeadmin.codegen.parser.SpecLoaderBridge;

import java.util.List;

/**
 * 测试桥接：SpecLoader.parse 是包私有的（它接受已解析的 Object，属于内部细节，
 * 不应作为公开 API）。测试需要直接喂 YAML 字符串以避免每个用例都写临时文件。
 */
final class SpecLoaderTestAccess {

    private SpecLoaderTestAccess() {
    }

    static ModuleSpec parse(Object raw) {
        return SpecLoaderBridge.parse(raw, "test.yaml");
    }

    static ModuleSpec parse(Object raw, String layoutOverride) {
        return SpecLoaderBridge.parse(raw, "test.yaml", layoutOverride);
    }

    static SpecLoader.LayoutChoice resolveLayout(String cliOverride, Object specValue,
                                                 String envValue, List<String> errors) {
        return SpecLoaderBridge.resolveLayout(cliOverride, specValue, envValue, errors);
    }
}
