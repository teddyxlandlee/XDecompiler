/*
 * Copyright 2026 teddyxlandlee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
