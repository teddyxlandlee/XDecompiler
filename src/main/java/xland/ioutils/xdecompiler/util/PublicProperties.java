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

public class PublicProperties {
    public static String versionManifestUrl() {
        return System.getProperty("xdecompiler.download.mc.manifest", "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    }

    public static int downloadThreads() {
        return Integer.getInteger("xdecompiler.threads.download", 4);
    }

    public static int mappingThreads() {
        return Integer.getInteger("xdecompiler.threads.mapping", 6);
    }

    public static int remapThreads() {
        return Integer.getInteger("xdecompiler.threads.remap", 6);
    }

    public static String vineFlowerUrl() {
        return System.getProperty("xdecompiler.download.vineflower", "https://repo1.maven.org/maven2/org/vineflower/vineflower/1.9.2/vineflower-1.9.2.jar");
    }

    public static String vineFlowerSha512() {
        return System.getProperty("xdecompiler.download.vineflower.sha512", "28f8338d4d55ef9af72d2a9f065b3aade23871a6e13607c9d80db928aae40def55c02a9b6ff1d05e6f3a6bec92f5354d583608e47c0ac65496ef6fd1719144fb");
    }

    public static String fabricMaven() {
        return System.getProperty("xdecompiler.maven.fabric", "https://maven.fabricmc.net");
    }

    public static String vineFlowerLogDir() {
        return System.getProperty("xdecompiler.vineflower.log.dir", "logs");
    }
}
