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
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

public class CommonUtils {
    public static List<String> mergePreserveOrder(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;

        while (i < first.size() || j < second.size()) {
            while (i < first.size() && j < second.size()
                    && first.get(i).equals(second.get(j))) {
                out.add(first.get(i));
                i++;
                j++;
            }

            while (i < first.size() && !second.contains(first.get(i))) {
                out.add(first.get(i));
                i++;
            }

            while (j < second.size() && !first.contains(second.get(j))) {
                out.add(second.get(j));
                j++;
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

    private static final char[] CHAR_POOL_NANO_ID = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
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
