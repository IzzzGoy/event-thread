import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = true,
            androidVariantsToPublish = listOf("release"),
        )
    )

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(
        rootProject.group.toString(),
        project.name,
        rootProject.version.toString()
    )

    pom {
        name.set("Event Thread Test")
        description.set("Test tooling for Event Thread: static event-flow graph validation (cycles, orphan events, event-graph reporting) plus a watcher-based scenario/dynamic test DSL for asserting real dispatch traces and container state")
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
}
