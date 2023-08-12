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
package xland.ioutils.xdecompiler.mcmeta.libraries;

import java.net.MalformedURLException;
import java.net.URL;

public record MavenArtifact(String group, String name, String version, String classifier, String extension) {
    public String getPath() {
        return group.replace('.', '/') + '/' + name + '/' + version + '/' + filename();
    }

    public String filename() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('-').append(version);
        if (classifier != null)
            sb.append('-').append(classifier);
        return sb.append('.').append(extension).toString();
    }

    public static MavenArtifact of(String descriptor) {
        String[] pts = descriptor.split(":");
        // assert pts.length > 2
        final String domain = pts[0];
        final String name = pts[1];

        int last = pts.length - 1;
        int idx = pts[last].indexOf('@');

        final String ext;
        if (idx != -1) {
            ext = pts[last].substring(idx + 1);
            pts[last] = pts[last].substring(0, idx);
        } else {
            ext = "jar";
        }

        final String version = pts[2];
        final String classifier;
        if (pts.length > 3)
            classifier = pts[3];
        else
            classifier = null;
        return new MavenArtifact(domain, name, version, classifier, ext);
    }

    public URL atMaven(URL maven) {
        String path = maven.getPath();
        StringBuilder sb = new StringBuilder(path);
        if (!path.endsWith("/")) {
            sb.append('/');
        }
        sb.append(this.getPath());
        try {
            return new URL(
                    maven.getProtocol(),
                    maven.getHost(),
                    maven.getPort(),
                    sb.toString()
            );
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(group).append(':').append(name).append(':').append(version);
        if (classifier != null)
            sb.append(':').append(classifier);
        if (extension != null && !"jar".equals(extension))
            sb.append('@').append(extension);
        return sb.toString();
    }
}
