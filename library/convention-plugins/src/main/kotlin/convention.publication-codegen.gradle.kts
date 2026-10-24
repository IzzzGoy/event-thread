import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

// A Gradle plugin, not a multiplatform library: vanniktech also publishes the
// `<id>.gradle.plugin` marker, so consumers resolve it by plugin id from Maven Central.
eventThreadPublication(
    pomName = "Event Thread Codegen",
    pomDescription = "Gradle plugin generating typed, abstract Event Thread scopes",
    platform = GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true),
)
