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

public class HashMismatchException extends SecurityException {
    public HashMismatchException(String s) {
        super(s);
    }

    public static HashMismatchException of(String fn, String hashExpected, String hashActual) {
        return new HashMismatchException("Expected `" + fn + "` to be hashed " + hashExpected +
                ", got " + hashActual);
    }

    public static HashMismatchException ofSize(String fn, long sizeExpected, long sizeActual) {
        return new HashMismatchException("Expected size of " + fn + " is " + sizeExpected + ", got " + sizeActual);
    }
}
