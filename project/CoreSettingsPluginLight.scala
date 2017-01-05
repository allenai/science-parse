import org.allenai.plugins.{CoreDependencies, CoreRepositories, VersionInjectorPlugin}
import sbt.Keys._
import sbt._

object CoreSettingsPluginLight extends AutoPlugin {

  // Automatically add the VersionInjectorPlugin
  //override def requires: Plugins = VersionInjectorPlugin

  // Automatically enable the plugin (no need for projects to `enablePlugins(CoreSettingsPluginLight)`)
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val CoreResolvers = CoreRepositories.Resolvers
    val PublishTo = CoreRepositories.PublishTo
  }

  import autoImport._

  // Add the IntegrationTest config to the project. The `extend(Test)` part makes it so
  // classes in src/it have a classpath dependency on classes in src/test. This makes
  // it simple to share common test helper code.
  // See http://www.scala-sbt.org/release/docs/Testing.html#Custom+test+configuration
  override val projectConfigurations = Seq(Configurations.IntegrationTest extend Test)

  // These settings will be automatically applied to projects
  override def projectSettings: Seq[Setting[_]] = {
    Defaults.itSettings ++
    Seq(
      fork := true, // Forking for run, test is required sometimes, so fork always.
      // Use a sensible default for the logback appname.
      javaOptions += s"-Dlogback.appname=${name.value}",
      scalaVersion := CoreDependencies.defaultScalaVersion,
      scalacOptions ++= Seq("-target:jvm-1.7", "-Xlint", "-deprecation", "-feature"),
      javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
      resolvers ++= CoreRepositories.Resolvers.defaults,
      dependencyOverrides ++= CoreDependencies.loggingDependencyOverrides,
      dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value
    )
  }
}
