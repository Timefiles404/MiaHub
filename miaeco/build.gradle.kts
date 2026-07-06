// 地形扩散推理：ONNX Runtime 直接打进插件 jar（服务器上无 Maven 可达性风险）。
// 只保留 win-x64（开发机）与 linux-x64（服务器）两个平台 native 以控制体积。
val onnxRuntime: Configuration by configurations.creating
// 离线 dumpTerra 工具的运行时附加依赖（slf4j 控制台绑定 + gson；服务器上由 Paper 提供）
val terraTool: Configuration by configurations.creating

configurations.named("compileOnly") { extendsFrom(onnxRuntime) }

dependencies {
    onnxRuntime("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    "compileOnly"("com.google.code.gson:gson:2.10.1")
    "compileOnly"("org.slf4j:slf4j-api:2.0.9")
    terraTool("org.slf4j:slf4j-simple:2.0.9")
    terraTool("com.google.code.gson:gson:2.10.1")
}

tasks.jar {
    archiveBaseName.set("MiaEco")
    from(provider { onnxRuntime.map { zipTree(it) } }) {
        exclude("META-INF/**")
        exclude("ai/onnxruntime/native/osx-x64/**")
        exclude("ai/onnxruntime/native/osx-aarch64/**")
        exclude("ai/onnxruntime/native/linux-aarch64/**")
        exclude("**/*.pdb")   // Windows 调试符号 300MB+，运行不需要
    }
}

// 离线地形验证：真实权重跑扩散管线，导出高度场/群系/森林分区，供渲染与校验。
// 需先把权重放到 references/terrain-diffusion/weights（或 -Pmiaeco.modelDir=… 指定）。
tasks.register<JavaExec>("dumpTerra") {
    group = "verification"
    description = "Run diffusion pipeline offline, dump heights/biomes/regions to build/terradump"
    dependsOn("compileJava")
    mainClass.set("dev.timefiles.miaeco.tool.TerraDumpTool")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] },
        provider { terraTool }
    )
    jvmArgs("-Xmx6g")
    systemProperty("miaeco.modelDir",
        (findProperty("miaeco.modelDir")
            ?: rootProject.projectDir.parentFile.resolve("references/terrain-diffusion/weights").absolutePath).toString())
    args(layout.buildDirectory.dir("terradump").get().asFile.absolutePath)
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

// 离线氛围验证：合成地形上跑全部主题的六特征生成器，导出供顶视渲染核对分布。
tasks.register<JavaExec>("dumpAtmo") {
    group = "verification"
    description = "Dump atmosphere feature edits on synthetic terrain to build/atmodump/atmo.jsonl"
    dependsOn("compileJava")
    mainClass.set("dev.timefiles.miaeco.tool.AtmoDumpTool")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] }
    )
    args(layout.buildDirectory.dir("atmodump").get().asFile.absolutePath)
    // -Pmiaeco.debugRiver=1 时向 stderr 输出河道走廊/成河诊断
    if (project.hasProperty("miaeco.debugRiver")) {
        jvmArgs("-Dmiaeco.debugRiver=1")
    }
}
