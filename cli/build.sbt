javaOptions in run += s"-Xmx10G"

fork := true

mainClass in assembly := Some("org.allenai.scienceparse.RunSP")

assemblyMergeStrategy in assembly := {
  case "logback.xml" => MergeStrategy.first
  case x => (assemblyMergeStrategy in assembly).value.apply(x)
}

libraryDependencies ++= Seq(
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.7.9",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.7.9",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.7.9",
  "com.github.scopt" %% "scopt" % "3.4.0"
)
