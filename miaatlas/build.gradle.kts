// MiaAtlas：定制地图世界生成器（轮盘扇区布局）。纯 Bukkit API；
// diffusion 细节源通过 softdepend 复用 MiaEco 的推理管线（编译期 project 依赖，不打包）。
dependencies {
    "compileOnly"(project(":miaeco"))
}

tasks.jar {
    archiveBaseName.set("MiaAtlas")
    manifest {
        // 纯 API、零 NMS：跳过 Paper 运行时重映射（同 miaeco 0.21.1 教训）
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}

// 离线校验 + 全图预览渲染：build/atlasdump/{atlas_preview.png, section_*.png}
// 无需模型权重，纯合成（basic 模式）秒级迭代。
tasks.register<JavaExec>("dumpAtlas") {
    group = "verification"
    description = "Render wheel-world preview and run layout invariant checks"
    dependsOn("compileJava", "processResources")
    mainClass.set("dev.timefiles.miaatlas.tool.AtlasDumpTool")
    classpath = files(
        provider { project.the<JavaPluginExtension>().sourceSets["main"].output },
        provider { project.configurations["compileClasspath"] }
    )
    jvmArgs("-Xmx2g")
    systemProperty("miaatlas.seed", (findProperty("miaatlas.seed") ?: "20260721").toString())
    systemProperty("miaatlas.step", (findProperty("miaatlas.step") ?: "4").toString())
    args(layout.buildDirectory.dir("atlasdump").get().asFile.absolutePath)
}
