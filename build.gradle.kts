group = "dev.timefiles"
version = "0.2.3"

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    }

    tasks.withType<ProcessResources> {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    tasks.withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
