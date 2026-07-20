dependencies {
    compileOnly("me.clip:placeholderapi:2.11.6")
}

tasks.jar {
    archiveBaseName.set("MiaAttributes")
    manifest {
        // 纯 Bukkit API、零 NMS：声明 mojang 映射命名空间，Paper 跳过整只 jar 的运行时重映射
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}
