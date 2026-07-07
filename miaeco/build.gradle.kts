// 地形扩散推理：ONNX Runtime 的 Java 类打进插件 jar；native 库不进 jar——
// 首次使用时由 GpuRuntime 从 Maven 镜像下载解出（0.21.1 起，压 jar 体积并降低
// Paper 热更新 remap 的堆压力；离线 dumpTerra 走 compileClasspath 里的完整 jar 不受影响）。
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
    manifest {
        // 纯 Bukkit API、零 NMS：声明 mojang 映射命名空间 → Paper 跳过整只 jar 的
        // 运行时重映射（热更新时 remap 需数百 MB 堆，曾在小堆服务器上 OOM）
        attributes("paperweight-mappings-namespace" to "mojang")
    }
    from(provider { onnxRuntime.map { zipTree(it) } }) {
        exclude("META-INF/**")
        exclude("ai/onnxruntime/native/**")   // native 全部改为首次使用时下载
    }
}

// 配置迁移离线校验：-Pmiaeco.oldConfig=<旧配置> [-Pmiaeco.mergedOut=<输出>]
tasks.register<JavaExec>("checkConfigMigration") {
    group = "verification"
    description = "Merge an old config.yml against the bundled default (comment-preserving)"
    dependsOn("compileJava")
    mainClass.set("dev.timefiles.miaeco.util.ConfigMigrator")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] }
    )
    args(
        project.file("src/main/resources/config.yml").absolutePath,
        (findProperty("miaeco.oldConfig") ?: project.file("src/main/resources/config.yml").absolutePath).toString(),
        (findProperty("miaeco.mergedOut") ?: layout.buildDirectory.file("merged-config.yml").get().asFile.absolutePath).toString()
    )
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
    // 0.18.1 起会话从文件路径建（权重在 native 堆外），2G 堆即可跑通——顺带回归防护
    jvmArgs("-Xmx" + (findProperty("miaeco.dumpXmx") ?: "2g"))
    systemProperty("miaeco.modelDir",
        (findProperty("miaeco.modelDir")
            ?: rootProject.projectDir.parentFile.resolve("references/terrain-diffusion/weights").absolutePath).toString())
    // -Pmiaeco.device=gpu 走 CUDA EP（需 weights/gpu-natives + weights/cuda 就位）
    systemProperty("miaeco.device", (findProperty("miaeco.device") ?: "cpu").toString())
    args(layout.buildDirectory.dir("terradump").get().asFile.absolutePath)
}

// 离线河流地形平面图（edge=open + yscale，真实权重）：build/rivermap/river_map.png
tasks.register<JavaExec>("riverMap") {
    group = "verification"
    description = "Render an open-edge map with global rivers to build/rivermap"
    dependsOn("compileJava")
    mainClass.set("dev.timefiles.miaeco.tool.RiverMapTool")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] },
        provider { terraTool }
    )
    jvmArgs("-Xmx" + (findProperty("miaeco.dumpXmx") ?: "3g"))
    systemProperty("miaeco.modelDir",
        (findProperty("miaeco.modelDir")
            ?: rootProject.projectDir.parentFile.resolve("references/terrain-diffusion/weights").absolutePath).toString())
    systemProperty("miaeco.device", (findProperty("miaeco.device") ?: "cpu").toString())
    systemProperty("miaeco.mapSize", (findProperty("miaeco.mapSize") ?: "1024").toString())
    systemProperty("miaeco.mapSeed", (findProperty("miaeco.mapSeed") ?: "20260707").toString())
    systemProperty("miaeco.yscale", (findProperty("miaeco.yscale") ?: "2.0").toString())
    args(layout.buildDirectory.dir("rivermap").get().asFile.absolutePath)
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
