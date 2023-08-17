/*
 * Copyright 2023 teddyxlandlee
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

import joptsimple.OptionParser;
import xland.ioutils.xdecompiler.script.difftwo.DiffTwoScript;
import xland.ioutils.xdecompiler.script.gitrepo.GitRepoScript;
import xland.ioutils.xdecompiler.util.CommonUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Map;

public abstract class Script {
    protected abstract void runScript() throws Exception;

    protected interface ArgParser {
        Script parse(OptionParser parser) throws Exception;
    }

    protected static void start(ArgParser parser) {
        try {
            parser.parse(new OptionParser()).runScript();
        } catch (Exception e) {
            CommonUtils.sneakyThrow(e);
        }
    }

    protected static void printHelpAndExit(OptionParser parser) {
        try {
            parser.printHelpOn(System.out);
        } catch (Exception ignored) {
        }
        System.exit(-1);
    }

    private static final Map<String, Class<? extends Script>> SCRIPTS = Map.of(
            "gitrepo", GitRepoScript.class,
            "difftwo", DiffTwoScript.class
    );

    private static final ThreadLocal<Boolean> EXISTENCE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void main(String[] rawArgs) throws Throwable {
        if (EXISTENCE.get())
            throw new IllegalStateException("Calling myself");

        if (rawArgs.length == 0 || "--help".equals(rawArgs[0])) {
            printHelp0();
            return;
        }

        final String k = rawArgs[0].toLowerCase(Locale.ROOT);
        if (SCRIPTS.containsKey(k)) {
            var lookup = MethodHandles.lookup();
            final Class<? extends Script> c = SCRIPTS.get(k);
            var handle = lookup.findStatic(c, "main", MethodType.methodType(void.class, String[].class));

            final int len = rawArgs.length - 1;
            String[] args = new String[len];
            System.arraycopy(rawArgs, 1, args, 0, len);

            try {
                EXISTENCE.set(Boolean.TRUE);
                handle.asFixedArity().bindTo(args).invoke();
            } finally {
                EXISTENCE.remove();
            }
        } else {
            printHelp0();
        }
    }

    private static void printHelp0() {
        System.out.printf("Usage: java -cp XDecompiler.jar %s " +
                "<script> <args...>%n" +
                "Available scripts: %s%n",
                Script.class.getName(), SCRIPTS.keySet());
    }
}
