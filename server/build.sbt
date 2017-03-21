
description := "Java server to extract titles, authors, abstracts, body text, and bibliographies from scholarly documents"

organization := "org.allenai.scienceparse"

// We still have to disable these specifically. I'm not sure why.
disablePlugins(CoreSettingsPlugin, SbtScalariform, StylePlugin)

enablePlugins(LibraryPluginLight)

javaOptions in run += s"-Xmx10G"

fork := true

mainClass in assembly := Some("org.allenai.scienceparse.SPServer")

assemblyMergeStrategy in assembly := {
  case "logback.xml" => MergeStrategy.first
  case "application.conf" => MergeStrategy.concat
  case x => (assemblyMergeStrategy in assembly).value.apply(x)
}

libraryDependencies ++= Seq(
  "org.slf4j" % "jcl-over-slf4j" % "1.7.7",
  "org.eclipse.jetty" % "jetty-server" % "9.4.1.v20170120",
  "com.typesafe" % "config" % "1.3.1",
  "org.scalikejdbc" %% "scalikejdbc" % "2.5.0" exclude ("commons-logging", "commons-logging"),
  "org.postgresql" % "postgresql" % "42.0.0"
)
