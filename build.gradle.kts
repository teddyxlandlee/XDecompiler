import java.time.ZoneId
import java.time.ZonedDateTime

plugins {
    id("java")
    id("com.diffplug.spotless") version "6.20.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "xland.ioutils"
version = project.property("app_version")!!

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net")
}

tasks.withType(JavaCompile::class).configureEach {
    options.release.set(17)
    options.encoding = "utf8"
}

tasks.shadowJar {
    archiveClassifier.set("fat")
}

tasks.build {
    dependsOn("shadowJar")
}

spotless {
    java {
        licenseHeaderFile("header.txt").updateYearWithLatest(true)
    }
}

dependencies {
    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")
    implementation("net.fabricmc:mapping-io:0.4.2")
    implementation("net.fabricmc:tiny-remapper:0.8.7")
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation("org.ow2.asm:asm-tree:9.5")
    implementation("org.ow2.asm:asm-util:9.5")
    implementation("org.sharegov:mjson:1.4.1") {
        exclude(group = "junit")
    }
    implementation("org.slf4j:slf4j-api:2.0.7")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.7")
    compileOnly("org.jetbrains:annotations:23.0.0")

    testCompileOnly("net.fabricmc:stitch:0.6.2")
}

tasks.processResources {
    from("license.txt") {
        rename { "LICENSE_${project.name}.txt" }
    }
}

tasks.jar {
    manifest.attributes(
    	"Main-Class" to "xland.ioutils.xdecompiler.Main",
    	"Implementation-Title" to "XDecompiler",
    	"Implementation-Version" to project.version,
    	"Implementation-Vendor" to "teddyxlandlee",
    	"Specification-Title" to "XDecompiler",
    	"Specification-Version" to project.property("spec_version"),
    	"Specification-Vendor" to "teddyxlandlee",
    	"Implementation-Timestamp" to "${ZonedDateTime.now(ZoneId.of("+08:00")).withNano(0)}",
    	"Automatic-Module-Name" to "xland.ioutils.xdecompiler",
    )
}
