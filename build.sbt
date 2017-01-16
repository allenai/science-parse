import sbtrelease.ReleaseStateTransformations._

// We still have to disable these specifically. I'm not sure why.
disablePlugins(CoreSettingsPlugin, SbtScalariform, StylePlugin)

enablePlugins(LibraryPluginLight)

name := "science-parse"

organization := "org.allenai"

description := "Java library to extract titles, authors, abstracts, body text, and bibliographies from scholarly documents"

outputStrategy := Some(StdoutOutput)

javaOptions in Test += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

javaOptions in Test += s"-Xmx12G"

javaOptions in run += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

sources in (Compile,doc) := Seq.empty

fork := true

connectInput in run := true

//
// Release settings
//

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
)

bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}"

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))

homepage := Some(url("https://github.com/allenai/science-parse"))

scmInfo := Some(ScmInfo(
  url("https://github.com/allenai/science-parse"),
  "https://github.com/allenai/science-parse.git"))

bintrayRepository := "private"

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

releasePublishArtifactsAction := PgpKeys.publishSigned.value

pomExtra :=
  <developers>
    <developer>
      <id>allenai-dev-role</id>
      <name>Allen Institute for Artificial Intelligence</name>
      <email>dev-role@allenai.org</email>
    </developer>
  </developers>


resolvers ++= Seq(
  "AllenAI Bintray" at "http://dl.bintray.com/allenai/maven",
  "AllenAI Bintray Private" at "http://dl.bintray.com/allenai/private",
  Resolver.jcenterRepo
)

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "1.2.1" excludeAll (
    ExclusionRule(organization = "org.apache.common", name = "commons-math3")
  ),
  "org.apache.pdfbox" % "pdfbox" % "2.1.0-AI2-ef339b" exclude ("commons-logging", "commons-logging"),
  "org.apache.pdfbox" % "fontbox" % "2.1.0-AI2-ef339b" exclude ("commons-logging", "commons-logging"),
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "org.allenai" % "ml" % "0.16" excludeAll (
    ExclusionRule(organization = "args4j"),
    ExclusionRule(organization = "org.slf4j", name="slf4j-simple")
  ),
  "org.projectlombok" % "lombok" % "1.16.6",
  "com.goldmansachs" % "gs-collections" % "6.1.0",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.7.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.7.2",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.7.2",
  "org.scalatest" %% "scalatest" % "2.2.1" % Test,
  "org.testng" % "testng" % "6.8.1" % Test,
  "org.allenai.common" %% "common-testkit" % "1.0.20" % Test,
  "org.allenai.datastore" %% "datastore" % "1.0.8" excludeAll(
    ExclusionRule(organization = "com.amazonaws")
  ),
  "com.amazonaws" % "aws-java-sdk" % "1.7.4",
  "org.bouncycastle" % "bcprov-jdk16" % "1.46",
  "org.bouncycastle" % "bcmail-jdk16" % "1.46",
  "com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images
  "com.levigo.jbig2" % "levigo-jbig2-imageio" % "1.6.5", // For handling jbig2 images
  "org.jsoup" % "jsoup" % "1.8.1",
  "org.apache.commons" % "commons-lang3" % "3.4",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.github.scopt" %% "scopt" % "3.4.0",
  "com.amazonaws" % "aws-java-sdk" % "1.7.4",
  "com.medallia.word2vec" %% "word2vecjava" % "1.0-ALLENAI-4",
  "com.google.guava" % "guava" % "18.0",
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0-RC3",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.allenai" %% "pdffigures2" % "0.0.7",
  "org.allenai.nlpstack" %% "nlpstack-tokenize" % "1.17" excludeAll (
    ExclusionRule(organization = "org.allenai.common") // nlpstack depends on ancient versions of these
  )
)
