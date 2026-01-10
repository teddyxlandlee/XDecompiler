package xland.ioutils.xdecompiler.script;

import xland.ioutils.xdecompiler.script.difftwo.DiffTwoScript;
import xland.ioutils.xdecompiler.script.gitrepo.GitRepoScript;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collector;

public final class BuiltinScriptProvider implements ScriptProvider {
    @Override
    public Map<String, Class<? extends Script>> scripts() {
        return Map.of(
                "gitrepo", GitRepoScript.class,
                "difftwo", DiffTwoScript.class
        );
    }

    private static final Object LOCK = new Object();
    private static volatile Map<String, Class<? extends Script>> availableScripts;

    static Map<String, Class<? extends Script>> getAvailableScripts() {
        Map<String, Class<? extends Script>> scripts = availableScripts;
        if (scripts == null) {
            synchronized (LOCK) {
                scripts = availableScripts;
                if (scripts == null) {
                    availableScripts = scripts = findAvailableScripts();
                }
            }
        }
        return scripts;
    }

    private static Map<String, Class<? extends Script>> findAvailableScripts() {
        return ServiceLoader.load(ScriptProvider.class).stream()
                .map(p -> p.get().scripts())
                .collect(Collector.of(
                        LinkedHashMap::new,
                        LinkedHashMap::putAll,
                        (LinkedHashMap<String, Class<? extends Script>> m1, LinkedHashMap<String, Class<? extends Script>> m2) -> {
                            m1.putAll(m2);
                            return m1;
                        },
                        Collections::unmodifiableMap
                ));
    }
}
