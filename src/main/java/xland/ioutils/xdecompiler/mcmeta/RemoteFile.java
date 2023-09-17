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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.function.Supplier;

public record RemoteFile(URL url, String hash, @Nullable Long size, Supplier<MessageDigest> mdFactory) {
    public static RemoteFile create(String url, String sha1) throws MalformedURLException {
        return new RemoteFile(new URL(url), sha1, null, HashingUtil::sha1);
    }

    public static RemoteFile create(String url, String sha1, long size) throws MalformedURLException {
        return new RemoteFile(new URL(url), sha1, size, HashingUtil::sha1);
    }

    public boolean matchHash(Path path) throws IOException {
        return HashingUtil.matchesFileHash(path, hash(), mdFactory());
    }

    public void download(OutputStream output) throws IOException, HashMismatchException {
        try (InputStream is = url.openStream();
             DigestOutputStream dos = new DigestOutputStream(output, mdFactory().get())) {
            long realSize = is.transferTo(dos);
            if (size != null && size != realSize)
                throw HashMismatchException.ofSize(url.toString(), size, realSize);

            final byte[] digest = dos.getMessageDigest().digest();
            if (HashingUtil.isSame(hash(), digest)) return;
            throw HashMismatchException.of(url.toString(), hash(), HashingUtil.stringify(digest));
        }
    }

    public void download(Path path) throws IOException, HashMismatchException {
        try (OutputStream output = Files.newOutputStream(path)) {
            download(output);
        }
    }

    public InputStream openFilteredInputStream() throws IOException {
        DigestInputStream is = new DigestInputStream(url.openStream(), mdFactory().get());
        if (!xland.ioutils.xdecompiler.util.DebugUtils.flagged(0))
            return new FilterInputStream(is) {
                @Override
                public void close() throws IOException {
                    byte[] b;
                    if (HashingUtil.isSame(hash(), (b = is.getMessageDigest().digest())))
                        return;
                    throw new IOException("Hash mismatched", HashMismatchException.of(url.toString(), hash(), HashingUtil.stringify(b)));
                }
            };  // don't check size, only hash
        else return new FilterInputStream(is) {
            long bytes;
            boolean lock;

            @Override
            public void close() throws IOException {
                super.close();
                byte[] h = null;
                if ((size == null || size == bytes) && HashingUtil.isSame(hash(), (h = is.getMessageDigest().digest())))
                    return;
                final StringBuilder sb = new StringBuilder();
                if (h == null) {
                    h = is.getMessageDigest().digest();
                    sb.append("Expected size (").append(size).append(") != actual size (").append(bytes).append("). ");
                }
                if (!HashingUtil.isSame(hash(), h))
                    sb.append("Expected hash (").append(hash()).append(") != actual hash (").append(HashingUtil.stringify(h)).append("). ");

                throw new IOException("Size or hash mismatched", new HashMismatchException(sb.toString()));
            }

            @Override
            public int read() throws IOException {
                final int read = super.read();
                if (!lock && read >= 0) bytes++;
                return read;
            }

            @Override
            public int read(byte @NotNull [] b, int off, int len) throws IOException {
                boolean wasLock = lock;
                lock = true;
                return add(super.read(b, off, len), wasLock);
            }

            private int add(int b, boolean wasLock) {
                if (wasLock) return b;
                bytes += b;
                lock = false;
                return b;
            }

            @Override
            public int read(byte @NotNull [] b) throws IOException {
                boolean wasLock = lock;
                lock = true;
                return add(super.read(b), wasLock);
            }
        };
    }
}
