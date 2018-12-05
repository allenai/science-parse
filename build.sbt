ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet

scalaVersion := "2.11.12"

lazy val commonSettings = Seq(
  resolvers += Resolver.jcenterRepo,
  javaOptions += s"-Dlogback.appname=${name.value}",
  scalaVersion := "2.11.12",
  // release settings
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/allenai/science-parse")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/allenai/science-parse"),
    "https://github.com/allenai/science-parse.git")),
  bintrayRepository := "maven",
  bintrayOrganization := Some("allenai"),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  pomExtra :=
    <developers>
      <developer>
        <id>allenai-dev-role</id>
        <name>Allen Institute for Artificial Intelligence</name>
        <email>dev-role@allenai.org</email>
      </developer>
    </developers>
)

skip in publish := true

lazy val core = (project in file("core")).
  settings(
    commonSettings
  )

lazy val cli = (project in file("cli")).
  settings(
    commonSettings
  ).dependsOn(core)

lazy val server = (project in file("server")).
  settings(
    commonSettings
  ).dependsOn(core)
