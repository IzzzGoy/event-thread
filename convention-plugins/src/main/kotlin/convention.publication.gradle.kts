import java.util.*

plugins {
    `maven-publish`
    //signing
}

val props = Properties()
props.load(project.rootProject.file("local.properties").inputStream())
val token = props.getProperty("token") ?: System.getProperty("token") ?: error("Missing token!")

/*val secretPropsFile = project.rootProject.file("local.properties")
if (secretPropsFile.exists()) {
    secretPropsFile.reader().use {
        Properties().apply {
            load(it)
        }
    }.onEach { (name, value) ->
        ext[name.toString()] = value
    }
} else {
    ext["signing.keyId"] = System.getenv("SIGNING_KEY_ID")
    ext["signing.password"] = System.getenv("SIGNING_PASSWORD")
    ext["signing.secretKeyRingFile"] = System.getenv("SIGNING_SECRET_KEY_RING_FILE")
    ext["ossrhUsername"] = System.getenv("OSSRH_USERNAME")
    ext["ossrhPassword"] = System.getenv("OSSRH_PASSWORD")
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc-${this.name}")
}

fun getExtraString(name: String) = ext[name]?.toString()*/

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/IzzzGoy/event-thread")
            credentials {
                username = "IzzzGoy"
                password = token
            }
        }

        /*maven {
            name = "sonatype"
            setUrl("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = getExtraString("ossrhUsername")
                password = getExtraString("ossrhPassword")
            }
        }*/
    }
    publications {
        register<MavenPublication>("gpr") {
            groupId = "ru.alexey.event.threads"
            artifactId = "event-thread-core"
            version = "0.0.1-dev02"
            from(components["kotlin"])
        }

        /*withType<MavenPublication> {

            groupId = "io.github.izzzgoy"
            artifactId = "core"
            version = "0.0.1-dev01"

            // Stub javadoc.jar artifact
            artifact(javadocJar.get())

            // Provide artifacts information requited by Maven Central
            pom {
                name.set("MPP Sample library")
                description.set("Sample Kotlin Multiplatform library (jvm + ios + js) test")
                url.set("https://github.com/IzzzGoy/event-thread")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("FromGoy")
                        name.set("Alexey")
                        email.set("xzadmoror@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/IzzzGoy/event-thread")
                }

            }

        }*/
    }
}

/*signing {
    sign(publishing.publications.first())
}*/

