dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
}

val selfUpdaterJar = project(":miahub-self-updater").tasks.named<Jar>("jar")

tasks.jar {
    archiveBaseName.set("MiaHub")

    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })

    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

tasks.processResources {
    dependsOn(selfUpdaterJar)
    from(rootProject.file("catalog.json"))
    from(selfUpdaterJar.flatMap { it.archiveFile }) {
        into("self-updater")
        rename { "MiaHubSelfUpdater.jar" }
    }
}
