import sbtrelease.ReleaseStateTransformations._

ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet

lazy val commonSettings = Seq(
  organization := "org.allenai",
  resolvers ++= Seq(
    "AllenAI Bintray" at "http://dl.bintray.com/allenai/maven",
    Resolver.jcenterRepo
  ),
  // assembly settings
  assemblyJarName in assembly := s"science-parse-${name.value}-${version.value}.jar",
  // release settings
  releaseProcess := Seq(
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  ),
  bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}",
  licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/allenai/science-parse")),
  scmInfo := Some(ScmInfo(
    url("https://github.com/allenai/science-parse"),
    "https://github.com/allenai/science-parse.git")),
  bintrayRepository := "private",
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  pomExtra :=
    <developers>
      <developer>
        <id>allenai-dev-role</id>
        <name>Allen Institute for Artificial Intelligence</name>
        <email>dev-role@allenai.org</email>
      </developer>
    </developers>
)

// disable release in the root project
publishArtifact := false
publishTo := Some("dummy" at "nowhere")
publish := { }
publishLocal := { }

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
