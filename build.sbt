import Dependencies._

enablePlugins(OfflinePlugin)

name := "science-parse"

version := "0.0.1"

description := ""

javaOptions in Test += s"-Dlogback.configurationFile=${baseDirectory.value}/../../pipeline/conf/logback-test.xml"

libraryDependencies ++= Seq(
  allenAiCommon,
  pdfbox,
  fontbox,
  jcl_over_slf4j,
  "org.allenai" % "ml" % "0.9" excludeAll (ExclusionRule(organization = "args4j")),
  "org.projectlombok" % "lombok" % "1.16.6",
  "com.goldmansachs" % "gs-collections" % "6.1.0",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.5.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.5.2",
  "org.testng" % "testng" % "6.8.1" % Test
)
