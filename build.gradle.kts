import java.time.ZoneId
import java.time.ZonedDateTime

plugins {
    id("java")
    id("com.diffplug.spotless") version "8.2.1"
    id("com.gradleup.shadow") version "9.3.1"
    `maven-publish`
}

group = "xland.ioutils"
version = project.property("app_version")!!

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType(JavaCompile::class).configureEach {
    options.release.set(25)
    options.encoding = "utf8"
}

//val vineflowerDecompiler: SourceSet by sourceSets.creating

tasks.shadowJar {
    archiveClassifier.set("fat")
}

tasks.build {
    dependsOn("shadowJar")
}

spotless {
    java {
        licenseHeaderFile("header.txt")
    }
}

dependencies {
    val asmVersion = project.property("asm_version")!!

    implementation("net.sf.jopt-simple:jopt-simple:5.0.4")
    implementation("net.fabricmc:mapping-io:0.8.0")
    implementation("net.fabricmc:tiny-remapper:0.13.0")
    implementation("org.ow2.asm:asm:${asmVersion}")
    implementation("org.ow2.asm:asm-commons:${asmVersion}")
    implementation("org.ow2.asm:asm-tree:${asmVersion}")
    implementation("org.ow2.asm:asm-util:${asmVersion}")
    implementation("org.sharegov:mjson:1.4.2") {
        exclude(group = "junit")
    }
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    compileOnly("org.jetbrains:annotations:26.0.2")

    testCompileOnly("org.vineflower:vineflower:1.11.2")
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
        "Specification-Version" to project.property("spec_version")!!,
        "Specification-Vendor" to "teddyxlandlee",
        "Implementation-Timestamp" to "${ZonedDateTime.now(ZoneId.of("+08:00")).withNano(0)}",
        "Automatic-Module-Name" to "xland.ioutils.xdecompiler",
    )
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components.named("java").get())
        }
    }
    repositories {
//        mavenLocal()
    }
}
