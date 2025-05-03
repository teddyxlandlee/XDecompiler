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
                        "log", "TRACE"
                )
                .libraries(classpath.toArray(File[]::new))
                .logger(new PrintStreamLogger(logStream))
                .build();
        decompiler.decompile();
    }
}
