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
package xland.ioutils.xdecompiler.decompile.vineflower;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;

import java.io.File;
import java.io.PrintStream;
import java.util.Collection;

@SuppressWarnings("unused")
public record VineFlowerEntrypoint(File jarIn, Collection<File> classpath, File dirOut, PrintStream logStream) implements Runnable {
    public void run() {
        var decompiler = Decompiler.builder()
                .inputs(jarIn)
                .output(new DirectoryResultSaver(dirOut))
                .options(
                        "asc", 1,
                        "iec", 1,
                        "iib", 1,
                        "bsm", 1,
                        "log", "INFO"
                )
                .libraries(classpath.toArray(File[]::new))
                .logger(new PrintStreamLogger(logStream))
                .build();
        decompiler.decompile();
    }
}
