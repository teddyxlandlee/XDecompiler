package xland.ioutils.xdecompiler.merge;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ClassMerger {
    @Contract("null, null -> fail")
    byte @NotNull [] merge(byte @Nullable [] clientClass, byte @Nullable[] serverClass);
}
