plugins {
    java
}

group = "dev.timefiles"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.14.0")
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("MiaHub")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        })

        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
