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
package xland.ioutils.xdecompiler.util;

import org.slf4j.Logger;

import java.util.function.Consumer;

public final class DebugUtils {
    private static final int DEBUG_FLAG = Integer.getInteger("xdecompiler.internal.debug", 0);
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void log(int i, Consumer<Logger> c) {
        if (flagged(i))
            c.accept(LOGGER);
    }

    public static boolean flagged(int i) {
        return ((1 << i) & DEBUG_FLAG) != 0;
    }

    private DebugUtils() {}
}
