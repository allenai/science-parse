
description := "Java CLI to extract titles, authors, abstracts, body text, and bibliographies from scholarly documents"

// We still have to disable these specifically. I'm not sure why.
disablePlugins(CoreSettingsPlugin, SbtScalariform, StylePlugin)

enablePlugins(LibraryPluginLight)

javaOptions in run += s"-Xmx10G"

fork := true

mainClass in assembly := Some("org.allenai.scienceparse.RunSP")

assemblyMergeStrategy in assembly := {
  case "logback.xml" => MergeStrategy.first
  case x => (assemblyMergeStrategy in assembly).value.apply(x)
}

libraryDependencies ++= Seq(
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "com.fasterxml.jackson.core" % "jackson-core" % "2.7.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.7.2",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.7.2",
  "com.github.scopt" %% "scopt" % "3.4.0"
)
