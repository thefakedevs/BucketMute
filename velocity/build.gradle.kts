plugins {
    kotlin("jvm") version "2.0.20-Beta1"
    kotlin("kapt") version "2.0.20-Beta1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("eclipse")
    id("xyz.jpenilla.run-velocity") version "2.3.1"
}

fun getGitCommitHash(): String {
    return try {
        val stdout = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get()
        stdout.trim()
    } catch (_: Exception) {
        "unknown"
    }
}

group = "dev.baechka"
version = "1.0.0-${getGitCommitHash()}"

base {
    archivesName.set("bucketmute-velocity")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "henkelmax"
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")
    compileOnly("net.luckperms:api:5.4")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "dev.baechka.bucketmute.kotlin")
        relocate("org.jetbrains", "dev.baechka.bucketmute.jetbrains")
        manifest {
            attributes["Main-Class"] = "dev.baechka.BucketMute.BucketMute"
        }
    }

    jar {
        manifest {
            attributes["Main-Class"] = "dev.baechka.BucketMute.BucketMute"
        }
    }

    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.4.0-SNAPSHOT")
    }
}

val targetJavaVersion = 17
kotlin {
    jvmToolchain(targetJavaVersion)
}

val templateSource = file("src/main/templates")
val templateDest = layout.buildDirectory.dir("generated/sources/templates")
val generateTemplates = tasks.register<Copy>("generateTemplates") {
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(templateSource)
    into(templateDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateTemplates.map { it.outputs }) }

project.eclipse.synchronizationTasks(generateTemplates)
