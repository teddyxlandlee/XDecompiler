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
package xland.ioutils.xdecompiler.merge;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.ClassFileFormatVersion;
import java.nio.ByteOrder;

final class ClassVersionUtil {
    public static Object classVersionBytes(byte[] classBuffer) {
        final int majorVersion = (short) ARRAY_ACCESS.get(classBuffer, 6);

        try {
            return ClassFileFormatVersion.fromMajor(majorVersion);
        } catch (IllegalArgumentException _) {  // invalid
            return "0x".concat(Integer.toHexString(majorVersion));
        }
    }

    private static final VarHandle ARRAY_ACCESS = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
}
