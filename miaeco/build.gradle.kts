tasks.jar {
    archiveBaseName.set("MiaEco")
}

// 离线开发工具：导出各树种×阶段×体型的生成结构（无需服务器），供渲染核对形态。
// classpath 用 provider 延迟解析（java 插件由根脚本 subprojects 注入，避免时序问题）。
tasks.register<JavaExec>("dumpTrees") {
    group = "verification"
    description = "Dump generated tree structures to build/treedump/trees.jsonl"
    dependsOn("compileJava")
    mainClass.set("dev.timefiles.miaeco.tool.TreeDumpTool")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] }
    )
    args(layout.buildDirectory.dir("treedump").get().asFile.absolutePath)
}
