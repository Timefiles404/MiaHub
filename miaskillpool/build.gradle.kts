dependencies {
    compileOnly(files(rootProject.layout.projectDirectory.dir("../references").file("MythicMobsPremium-5.12.0-SNAPSHOT.jar")))
}

tasks.jar {
    archiveBaseName.set("MiaSkillpool")
}
