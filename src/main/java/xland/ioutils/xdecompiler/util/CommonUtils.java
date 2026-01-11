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

import java.security.SecureRandom;
import java.util.*;
import java.util.function.*;
import java.util.random.RandomGenerator;

public final class CommonUtils {
    public static <E, K> List<E> mergePreserveOrder(List<? extends E> first, List<? extends E> second, Function<? super E, ? extends K> keyExtractor) {
        var result = new ArrayList<E>(first.size() + second.size());
        Merger.<E>samePath(dropSecond(), result::add).mergePreserveOrder(first, second, keyExtractor);
        new Merger<E>(result::add, result::add, dropSecond(result::add)).mergePreserveOrder(first, second, Function.identity());
        return result;
    }

    public static void sneakyThrow(Throwable t) {
        sneakyThrow0(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow0(Throwable t) throws T {
        throw (T) t;
    }

    public static <E> BiConsumer<E, E> dropSecond(Consumer<? super E> consumer) {
        return (a, _) -> consumer.accept(a);
    }

    public static <E> BinaryOperator<E> dropSecond() {
        return (a, _) -> a;
    }

    private static final String CHAR_POOL_NANO_ID = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final RandomGenerator RANDOM_NANO_ID = new SecureRandom();
    private static final int NANO_ID_DEFAULT_LEN = 16;

    public static String newNanoID(int len) {
        return RANDOM_NANO_ID.ints(len, 0, CHAR_POOL_NANO_ID.length())
                .map(CHAR_POOL_NANO_ID::charAt)
                .collect(StringBuilder::new, (sb, i) -> sb.append((char)i), StringBuilder::append)
                .toString();
    }

    public static String newNanoID() {
        return newNanoID(NANO_ID_DEFAULT_LEN);
    }

    private CommonUtils() {}
}
