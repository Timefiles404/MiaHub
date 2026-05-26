group = "dev.timefiles"

val paperApiVersion = providers.gradleProperty("paperApiVersion").get()

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = providers.gradleProperty("${project.name}.version").get()

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:$paperApiVersion")
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
