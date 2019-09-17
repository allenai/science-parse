ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet

lazy val scala211 = "2.11.12"
lazy val scala212 = "2.12.9"
lazy val scala213 = "2.13.0" // Not supported yet (collections changes required)
lazy val supportedScalaVersions = List(scala212, scala211)

ThisBuild / organization := "org.allenai"
ThisBuild / scalaVersion := scala212
ThisBuild / name         := "science-parse"
ThisBuild / version      := "3.0.0"

lazy val commonSettings = Seq(
  crossScalaVersions := supportedScalaVersions,
  resolvers ++= Seq(
    Resolver.jcenterRepo,
    Resolver.bintrayRepo("allenai", "maven")
  ),
  javaOptions += s"-Dlogback.appname=${name.value}",
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

lazy val root = (project in file("."))
    .aggregate(
      core,
      cli,
      server
    )
    .settings(
      crossScalaVersions := Nil,
      publish / skip := true,
      commonSettings
    )

lazy val core = (project in file("core")).
  settings(
    commonSettings
  )

lazy val cli = (project in file("cli")).
  settings(
    description := "Java CLI to extract titles, authors, abstracts, body text, and bibliographies from scholarly documents",
    name := "science-parse-cli",
    commonSettings
  ).dependsOn(core)

lazy val server = (project in file("server")).
  settings(
    description := "Java server to extract titles, authors, abstracts, body text, and bibliographies from scholarly documents",
    name := "science-parse-server",
    commonSettings
  ).dependsOn(core)
