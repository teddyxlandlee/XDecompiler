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
package xland.ioutils.xdecompiler.script.gitrepo;

import org.jetbrains.annotations.Nullable;
import xland.ioutils.xdecompiler.util.CommonUtils;
import xland.ioutils.xdecompiler.util.Identified;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

enum ProcessType implements Identified {
    INITIALIZE("init", "source ./init.sh", false),
    CHECKOUT("checkout", /*alias*/ "xdecompiler_checkout", true),
    RUN_MAIN("run_main", /*alias*/ "xdecompiler_run", true),
    POST("post", /*alias*/ "source ./post.sh", false);

    private final String id;
    private final String defaultCommand;
    private final boolean allowsExternal;

    ProcessType(String id, String defaultCommand, boolean allowsExternal) {
        this.id = id;
        this.defaultCommand = defaultCommand;
        this.allowsExternal = allowsExternal;
    }

    void write(Appendable appendable, @Nullable String commandOverride, @Nullable String[] args) throws IOException {
        this.write(appendable, commandOverride, args, false);
    }

    private void write(Appendable appendable, @Nullable String commandOverride, @Nullable String[] args, boolean internal) throws IOException {
        if (!internal && !allowsExternal)
            throw new IllegalStateException("External call to " + this + ", which is not allowed!");

        if (args == null) args = new String[0];
        if (commandOverride == null) commandOverride = defaultCommand;

        appendable.append(commandOverride);
        for (String s : args)
            appendable.append(' ').append(s);

        if (!internal)
            appendable.append("\nif \"${XDECOMPILER_TERMINATES}\" ; then return 1 ; fi\n");
        else
            appendable.append("\nif test $? -ne 0 || \"${XDECOMPILER_TERMINATES}\" ; then exit 1 ; fi\n");
    }

    static void override(String arg, /*Mutable*/Map<ProcessType, String> defaultCommands) {
        final String[] split = arg.split(":", 2);
        if (split.length < 2)
            throw new IllegalArgumentException(arg + " is not a valid override argument");
        final ProcessType type = get(split[0]);
        if (type == null)
            throw new IllegalArgumentException("Couldn't find type: " + split[0]);
        if (!Objects.equals(type.defaultCommand, split[1]))
            defaultCommands.put(type, split[1]);
    }

    @Override
    public String id() {
        return id;
    }

    private static final Map<String, ProcessType> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Identified::id, Function.identity()));

    static ProcessType get(String id) {
        return BY_ID.get(id);
    }

    private static void writeCoreCommands(Appendable appendable,
                                          List<Map.Entry<ProcessType, String[]>> entryList,
                                          Map<ProcessType, String> defaultCommands) throws IOException {
        final String commandName = "xdecompiler-main-".concat(CommonUtils.newNanoID());
        appendable.append(commandName).append(" () {\n\n");

        for (Map.Entry<ProcessType, String[]> e : entryList) {
            final ProcessType type = e.getKey();
            type.write(appendable, defaultCommands.get(type), e.getValue());
        }

        appendable.append("\necho 'Done!'\n}\n").append(commandName).append('\n');
    }

    static void writeCommands(Appendable appendable,
                              List<Map.Entry<ProcessType, String[]>> entryList,
                              Map<ProcessType, String> defaultCommands)
            throws IOException, IllegalArgumentException {
        Map.Entry<ProcessType, String[]> first, last;
        final int size = entryList.size();
        if (size < 2)
            throw new IllegalArgumentException("Not enough arguments in entry list");
        first = entryList.getFirst();
        last = entryList.getLast();

        ProcessType type = first.getKey();
        type.write(appendable, defaultCommands.get(type), first.getValue(), true);

        writeCoreCommands(appendable, entryList.subList(1, size - 1), defaultCommands);

        type = last.getKey();
        type.write(appendable, defaultCommands.get(type), last.getValue(), true);
    }
}
