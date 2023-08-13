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

import static org.objectweb.asm.Opcodes.*;

final class ClassVersionUtil {
    private static int getClassVersion(byte[] classBuffer) {
        int offset = 6;
        return ((classBuffer[offset] & 0xFF) << 8) | (classBuffer[offset + 1] & 0xFF);
    }

    public static String classVersion1(byte[] classBuffer) {
        final String s = classVersion(classBuffer);
        if (s.startsWith("V")) return null;
        return s;
    }

    public static String classVersion(byte[] classBuffer) {
        return switch (getClassVersion(classBuffer)) {
            case V21  -> "V21";
            case V20  -> "V20";
            case V19  -> "V19";
            case V18  -> "V18";
            case V17  -> "V17";
            case V16  -> "V16";
            case V15  -> "V15";
            case V14  -> "V14";
            case V13  -> "V13";
            case V12  -> "V12";
            case V11  -> "V11";
            case V10  -> "V10";
            case V9   -> "V9";
            case V1_8 -> "V1_8";
            case V1_7 -> "V1_7";
            case V1_6 -> "V1_6";
            case V1_5 -> "V1_5";
            case V1_4 -> "V1_4";
            case V1_3 -> "V1_3";
            case V1_2 -> "V1_2";
            case V1_1 -> "V1_1";
            default -> Integer.toHexString(getClassVersion(classBuffer));
        };
    }
}
