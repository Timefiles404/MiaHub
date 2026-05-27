sourceSets {
    main {
        java.srcDir(rootProject.layout.projectDirectory.dir("compile-stubs/src/main/java"))
    }
}

dependencies {
    compileOnly(files(rootProject.layout.projectDirectory.dir("../references").file("MythicMobs.jar")))
}

tasks.jar {
    archiveBaseName.set("MiaPickaxe")
    exclude("me/clip/**", "net/milkbowl/**", "org/black_ixx/**")
}
