name := "science-parse"

javaOptions in Test += s"-Dlogback.configurationFile=${baseDirectory.value}/conf/logback-test.xml"

PublishTo.ai2Public

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "1.0.4" excludeAll (
    ExclusionRule(organization = "org.apache.common", name = "commons-math3")
  ),
  "org.apache.pdfbox-local" % "pdfbox" % "2.0.0-local",
  "org.apache.pdfbox-local" % "fontbox" % "2.0.0-local",
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "org.allenai" % "ml" % "0.9" excludeAll (ExclusionRule(organization = "args4j")),
  "org.projectlombok" % "lombok" % "1.16.6",
  "com.goldmansachs" % "gs-collections" % "6.1.0",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.5.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.5.2",
  "org.scalatest" %% "scalatest" % "2.2.1" % Test,
  "org.testng" % "testng" % "6.8.1" % Test
)
