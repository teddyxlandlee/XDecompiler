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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.random.RandomGenerator;

public class CommonUtils {
    public static <E> List<E> mergePreserveOrder(List<? extends E> first, List<? extends E> second) {
        if (first instanceof RandomAccess && second instanceof RandomAccess) {
            return mergePreserveOrderRA(first, second);
        } else {
            return mergePreserveOrderNRA(first, second);
        }
    }

    private static <E> List<E> mergePreserveOrderRA(List<? extends E> first, List<? extends E> second) {
        List<E> out = new ArrayList<>();
        Set<E> firstSet = new HashSet<>(first);
        Set<E> secondSet = new HashSet<>(second);

        int i = 0, j = 0;

        while (i < first.size() || j < second.size()) {
            // same index, same element
            while (i < first.size() && j < second.size() &&
                    Objects.equals(first.get(i), second.get(j))) {
                out.add(first.get(i));
                i++;
                j++;
            }

            // first-only
            while (i < first.size() && !secondSet.contains(first.get(i))) {
                out.add(first.get(i));
                i++;
            }

            // second-only
            while (j < second.size() && !firstSet.contains(second.get(j))) {
                out.add(second.get(j));
                j++;
            }

            // shared, unmatched index
            if (i < first.size() && j < second.size() &&
                    !Objects.equals(first.get(i), second.get(j))) {
                // add the first one, arbitrarily
                out.add(first.get(i));
                i++;
            }
        }

        return out;
    }

    private static <E> List<E> mergePreserveOrderNRA(List<? extends E> first, List<? extends E> second) {
        List<E> out = new ArrayList<>();
        Set<E> firstSet = new HashSet<>(first);
        Set<E> secondSet = new HashSet<>(second);

        ListIterator<? extends E> firstIter = first.listIterator();
        ListIterator<? extends E> secondIter = second.listIterator();

        while (firstIter.hasNext() && secondIter.hasNext()) {
            E firstElem = firstIter.next();
            E secondElem = secondIter.next();

            if (Objects.equals(firstElem, secondElem)) {
                out.add(firstElem);
            } else {
                // rollback
                firstIter.previous();
                secondIter.previous();
                break;
            }
        }

        // remaining of first
        while (firstIter.hasNext()) {
            E elem = firstIter.next();
            if (!secondSet.contains(elem)) {
                out.add(elem);
            }
        }

        // remaining of second
        while (secondIter.hasNext()) {
            E elem = secondIter.next();
            if (!firstSet.contains(elem)) {
                out.add(elem);
            }
        }

        return out;
    }

    public static void sneakyThrow(Throwable t) {
        sneakyThrow0(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow0(Throwable t) throws T {
        throw (T) t;
    }

    private static final byte[] CHAR_POOL_NANO_ID = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.ISO_8859_1);
    private static final RandomGenerator RANDOM_NANO_ID = new SecureRandom();
    private static final int NANO_ID_DEFAULT_LEN = 16;

    public static String newNanoID(int len) {
        return RANDOM_NANO_ID.ints(len, 0, CHAR_POOL_NANO_ID.length)
                .map(i -> CHAR_POOL_NANO_ID[i])
                .collect(StringBuilder::new, (sb, i) -> sb.append((char)i), StringBuilder::append)
                .toString();
    }

    public static String newNanoID() {
        return newNanoID(NANO_ID_DEFAULT_LEN);
    }
}
