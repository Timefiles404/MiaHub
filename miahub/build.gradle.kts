dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
}

tasks.jar {
    archiveBaseName.set("MiaHub")

    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })

    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

tasks.processResources {
    from(rootProject.file("catalog.json"))
}
