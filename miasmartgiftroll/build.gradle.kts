sourceSets {
    main {
        java.srcDir(rootProject.layout.projectDirectory.dir("compile-stubs/src/main/java"))
    }
}

tasks.jar {
    archiveBaseName.set("MiaSmartGiftRoll")
    exclude("me/clip/**", "net/milkbowl/**", "org/black_ixx/**")
}
