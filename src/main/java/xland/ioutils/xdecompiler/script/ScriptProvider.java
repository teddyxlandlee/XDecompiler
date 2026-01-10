package xland.ioutils.xdecompiler.script;

import java.util.Map;

public interface ScriptProvider {
    default Map<String, Class<? extends Script>> scripts() {
        return Map.of();
    }

    static Map<String, Class<? extends Script>> availableScripts() {
        return BuiltinScriptProvider.getAvailableScripts();
    }
}
