dependencies {
    compileOnly(files(rootProject.layout.projectDirectory.dir("../references").file("MythicMobs.jar")))
    compileOnly("me.clip:placeholderapi:2.11.6")
}

tasks.jar {
    archiveBaseName.set("MiaSkillpool")
}
