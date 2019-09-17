javaOptions in Test += s"-Xmx10G"

fork in Test := true

assemblyJarName in assembly := s"science-parse-${version.value}.jar"

libraryDependencies ++= Seq(
  "org.allenai.common" %% "common-core" % "2.0.0" excludeAll (
    ExclusionRule(organization = "org.apache.common", name = "commons-math3")
  ),
  "org.apache.pdfbox" % "pdfbox" % "2.0.9" exclude ("commons-logging", "commons-logging"),
  "org.apache.pdfbox" % "fontbox" % "2.0.9" exclude ("commons-logging", "commons-logging"),
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "org.allenai" % "ml" % "0.16" excludeAll (
    ExclusionRule(organization = "args4j"),
    ExclusionRule(organization = "org.slf4j", name="slf4j-simple")
  ),
  "org.projectlombok" % "lombok" % "1.16.20",
  "com.goldmansachs" % "gs-collections" % "6.1.0",
  "org.scalatest" %% "scalatest" % "2.2.1" % Test,
  "org.testng" % "testng" % "6.8.1" % Test,
  "org.allenai.common" %% "common-testkit" % "2.0.0" % Test,
  "org.allenai.datastore" %% "datastore" % "2.0.0",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.54",
  "org.bouncycastle" % "bcmail-jdk15on" % "1.54",
  "org.bouncycastle" % "bcpkix-jdk15on" % "1.54",
  "org.jsoup" % "jsoup" % "1.8.1",
  "org.apache.commons" % "commons-lang3" % "3.4",
  "commons-io" % "commons-io" % "2.4",
  "com.amazonaws" % "aws-java-sdk-s3" % "1.11.213" exclude ("commons-logging", "commons-logging"),
  "org.allenai.word2vec" %% "word2vecjava" % "2.0.0"
    exclude ("log4j", "log4j")
    exclude ("commons-logging", "commons-logging"),
  "com.google.guava" % "guava" % "18.0",
  "org.scala-lang.modules" %% "scala-java8-compat" % "0.8.0",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.allenai" %% "pdffigures2" % "0.1.0",
  "io.spray" %%  "spray-json" % "1.3.3",
  "de.ruedigermoeller" % "fst" % "2.47",
  "org.apache.opennlp" % "opennlp-tools" % "1.7.2"

  // So SP can parse more image formats
  // These are disabled by default, because they are not licensed flexibly enough.
  //"com.github.jai-imageio" % "jai-imageio-core" % "1.2.1",
  //"com.github.jai-imageio" % "jai-imageio-jpeg2000" % "1.3.0", // For handling jpeg2000 images
  //"com.levigo.jbig2" % "levigo-jbig2-imageio" % "1.6.5" // For handling jbig2 images
)
