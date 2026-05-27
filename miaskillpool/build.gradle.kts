dependencies {
    compileOnly(files(rootProject.layout.projectDirectory.dir("../references").file("MythicMobs.jar")))
}

tasks.jar {
    archiveBaseName.set("MiaSkillpool")
}
