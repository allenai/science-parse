name := "science-parse"

organization := "org.allenai"

javaOptions in Test += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

javaOptions in run += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

PublishTo.ai2Public

disableBintray()

sources in (Compile,doc) := Seq.empty

mainClass in assembly := Some("org.allenai.scienceparse.pdfapi.PDFMetadata")

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "1.1.1-SNAPSHOT" excludeAll (
    ExclusionRule(organization = "org.apache.common", name = "commons-math3")
  ),
  "org.apache.pdfbox" % "pdfbox" % "1.8.10" exclude ("commons-logging", "commons-logging"),
  "org.apache.pdfbox" % "fontbox" % "1.8.10" exclude ("commons-logging", "commons-logging"),
  "org.allenai.pdfbox" % "pdfbox" % "2.0.0-RC2" exclude ("commons-logging", "commons-logging"),
  "org.allenai.pdfbox" % "fontbox" % "2.0.0-RC2" exclude ("commons-logging", "commons-logging"),
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "org.allenai" % "ml" % "0.9" excludeAll (
    ExclusionRule(organization = "args4j"),
    ExclusionRule(organization = "org.slf4j", name="slf4j-simple")
    ),
  "org.projectlombok" % "lombok" % "1.16.6",
  "com.goldmansachs" % "gs-collections" % "6.1.0",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.5.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.5.2",
  "org.scalatest" %% "scalatest" % "2.2.1" % Test,
  "org.testng" % "testng" % "6.8.1" % Test,
  "org.allenai.common" %% "common-testkit" % "1.0.20" % Test,
  "com.github.scopt" %% "scopt" % "3.3.0"
)
