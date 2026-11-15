import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.io.File

// ===== 本机 IDE 自动发现 =====
// 扫描常见 JetBrains 安装根目录，按 product-info.json 内容识别安装（不依赖目录名，
// 目录改名/多 IDE 并存都能识别），IntelliJ IDEA 优先于 PyCharm，同产品取构建号最高者。
// 显式覆盖：用户级 ~/.gradle/gradle.properties 配 localIdePath=...（最高优先级）。
val jetbrainsRoots = listOf(
    "D:/software/JetBrains",
    "C:/Program Files/JetBrains",
    "C:/Program Files (x86)/JetBrains",
    "D:/JetBrains",
    "${System.getProperty("user.home")}/AppData/Local/JetBrains"
).map { File(it.replace('\\', '/')) }.filter { it.isDirectory }

data class IdeInstall(val dir: File, val productCode: String, val buildMajor: Int)

fun scoreIde(productCode: String, buildMajor: Int): Int {
    val base = when (productCode) {
        "IU" -> 500
        "IC" -> 400
        "PY" -> 300
        "PC" -> 290
        else -> 100
    }
    return base * 100000 + buildMajor
}

fun discoverIdeInstalls(): List<IdeInstall> {
    val installs = mutableListOf<IdeInstall>()
    val seen = mutableSetOf<String>()
    fun consider(dir: File) {
        if (!seen.add(dir.absolutePath)) return
        val info = File(dir, "product-info.json")
        if (!info.isFile) return
        val text = runCatching { info.readText() }.getOrDefault("")
        val code = Regex("\"productCode\"\\s*:\\s*\"([A-Z]+)\"").find(text)?.groupValues?.get(1) ?: return
        val build = Regex("\"buildNumber\"\\s*:\\s*\"([0-9][0-9.]*)\"").find(text)?.groupValues?.get(1) ?: return
        if (!File(dir, "lib").isDirectory) return
        val major = build.substringBefore('.').toIntOrNull() ?: return
        installs += IdeInstall(dir, code, major)
    }
    for (root in jetbrainsRoots) {
        root.listFiles { f -> f.isDirectory }?.forEach { child ->
            consider(child)
            // Toolbox 布局再深入一层（apps/<code>/ch-0/<version>）
            child.listFiles { f -> f.isDirectory }?.forEach { consider(it) }
        }
    }
    return installs
}

plugins {
    id("java")
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.codeime"
version = "1.5.17"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // JNA 由 IDE 自带（lib/ 下），只参与编译、不打进插件包，避免类加载冲突
    compileOnly("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation("net.java.dev.jna:jna-platform:5.17.0")
    // 探针（immProbe）独立于 IDE 运行，需要显式带上 stdlib
    testImplementation(kotlin("stdlib"))

    intellijPlatform {
        // 自动发现本机 IDE（IDEA 优先）；显式 localIdePath 属性最高优先；
        // 都没有时从 JetBrains 仓库下载分发版（首次约 1GB，较慢）
        val explicitIde = providers.gradleProperty("localIdePath").orNull
            ?.let { p -> File(p.replace('\\', '/')) }?.takeIf { it.isDirectory }
        val discovered = runCatching { discoverIdeInstalls() }.getOrNull()
            ?.maxByOrNull { scoreIde(it.productCode, it.buildMajor) }
        val ideDir: File? = explicitIde ?: discovered?.dir
        println("CodeIME build: IDE = ${ideDir?.absolutePath ?: "未发现本机安装，将下载分发版"}")
        if (ideDir != null) {
            local(ideDir.absolutePath)
        } else {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1.4")
        }
    }
}

kotlin {
    compilerOptions {
        // 字节码目标：IDEA 2026.1 平台要求的最低 Java 版本
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

java {
    // 平台最低要求 Java 21；本机没有对应 JDK 时由 foojay 自动下载
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "CodeIME"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
        }
    }

    pluginVerification {
        // 与上方自动发现保持一致：验证目标 = 本机 IDE（未发现时回退到推荐版本列表）
        val ideDir = providers.gradleProperty("localIdePath").orNull
            ?.let { p -> File(p.replace('\\', '/')) }?.takeIf { it.isDirectory }
            ?: runCatching { discoverIdeInstalls() }.getOrNull()
                ?.maxByOrNull { scoreIde(it.productCode, it.buildMajor) }?.dir
        ides {
            if (ideDir != null) {
                local(ideDir.absolutePath)
            } else {
                recommended()
            }
        }
    }

    publishing {
        // 令牌从 ~/.gradle/gradle.properties（intellijPublishToken=...）
        // 或环境变量 INTELLIJ_PUBLISH_TOKEN 读取，勿提交到仓库
        token = providers.gradleProperty("intellijPublishToken")
            .orElse(providers.environmentVariable("INTELLIJ_PUBLISH_TOKEN"))
            .orElse("")
    }
}

/** 独立验证 imm32 JNA 绑定：./gradlew immProbe */
tasks.register<JavaExec>("immProbe") {
    group = "verification"
    mainClass = "com.codeime.tools.ImmProbeKt"
    classpath = sourceSets["test"].runtimeClasspath
}

/** 独立验证 SendInput 原子注入（无害键 F15）：./gradlew sendInputProbe */
tasks.register<JavaExec>("sendInputProbe") {
    group = "verification"
    mainClass = "com.codeime.tools.SendInputProbeKt"
    classpath = sourceSets["test"].runtimeClasspath
}

/** 核心逻辑测试（状态机+假定模式+上下文判定+IMM32）：./gradlew toggleLogicProbe */
tasks.register<JavaExec>("toggleLogicProbe") {
    group = "verification"
    mainClass = "com.codeime.tools.ToggleLogicProbeKt"
    classpath = sourceSets["test"].runtimeClasspath
}
