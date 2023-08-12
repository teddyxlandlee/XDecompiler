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
package xland.ioutils.xdecompiler.mcmeta;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashingUtil {
    private static final String HEXMAP = "0123456789abcdef";

    public static MessageDigest sha1() {
        return known("sha1");
    }

    public static MessageDigest sha256() {
        return known("sha256");
    }

    public static MessageDigest sha512() {
        return known("sha512");
    }

    private static MessageDigest known(String a) {
        try {
            return MessageDigest.getInstance(a);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static boolean isSame(String s, byte[] b) {
        if (b.length << 1 != s.length()) return false;
        int k = 0;
        for (int x : b) {
            if (s.charAt(k++) != HEXMAP.charAt((x >> 4) & 15))
                return false;
            if (s.charAt(k++) != HEXMAP.charAt(x & 15))
                return false;
        }
        return true;
    }

    public static String stringify(byte[] b) {
        StringBuilder sb = new StringBuilder(32);
        for (byte c : b) {
            int d = c & 0xff;
            sb.append(String.format("%02x", d));
        }
        return sb.toString();
    }

    public static boolean matchesFileSha1(Path file, String sha1) throws IOException {
        if (!Files.exists(file)) return false;

        MessageDigest md = sha1();
//        byte[] b = new byte[4096];
        try (var in = Files.newInputStream(file)) {
            in.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), md));
        }
        return isSame(sha1, md.digest());
    }
}
