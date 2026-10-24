import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.Platform
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Maven Central publication for one event-thread module - shared by every
 * `convention.publication-*` script, which differ only in [pomName]/[pomDescription] (and, for the
 * Gradle plugin, [platform]).
 *
 * Applied only when the library is the root build (`./gradlew -p library ...`, as CI runs it), not
 * when it's included by the sample app at the repo root: the sample never publishes, and vanniktech
 * registers a Sonatype build service on apply whose state an *included* build can't restore from
 * the configuration cache ("SonatypeRepositoryBuildService not found in class loader ...") - that
 * broke the sample's cached builds whenever the library had also been built standalone in the
 * same daemon.
 */
fun Project.eventThreadPublication(
    pomName: String,
    pomDescription: String,
    platform: Platform = KotlinMultiplatform(
        javadocJar = JavadocJar.Empty(),
        sourcesJar = true,
        androidVariantsToPublish = listOf("release"),
    ),
) {
    if (gradle.parent != null) return

    pluginManager.apply("com.vanniktech.maven.publish")
    extensions.configure<MavenPublishBaseExtension> {
        configure(platform)

        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

        signAllPublications()

        coordinates(
            rootProject.group.toString(),
            project.name,
            rootProject.version.toString()
        )

        pom {
            name.set(pomName)
            description.set(pomDescription)
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
}
