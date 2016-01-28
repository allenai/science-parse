name := "science-parse"

organization := "org.allenai"

javaOptions in Test += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

javaOptions in Test += s"-Xmx8G"

javaOptions in run += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

sources in (Compile,doc) := Seq.empty

mainClass in assembly := Some("org.allenai.scienceparse.FigureExtractorBatchCli")

bintrayPackage := s"${organization.value}:${name.value}_${scalaBinaryVersion.value}"

resolvers += Resolver.bintrayRepo("allenai", "maven")

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "1.1.2" excludeAll (
    ExclusionRule(organization = "org.apache.common", name = "commons-math3")
  ),
  "org.allenai.pdfbox" % "pdfbox" % "2.0.0-AI2" exclude ("commons-logging", "commons-logging"),
  "org.allenai.pdfbox" % "fontbox" % "2.0.0-AI2" exclude ("commons-logging", "commons-logging"),
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
  "com.github.scopt" %% "scopt" % "3.3.0",
  "org.allenai" %% "datastore" % "1.0.2" % Test,
  "org.bouncycastle" % "bcprov-jdk16" % "1.46",
  "org.bouncycastle" % "bcmail-jdk16" % "1.46"
)
