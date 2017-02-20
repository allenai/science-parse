lazy val commonSettings = Seq(
  organization := "org.allenai",
  resolvers ++= Seq(
    "AllenAI Bintray" at "http://dl.bintray.com/allenai/maven",
    "AllenAI Bintray Private" at "http://dl.bintray.com/allenai/private",
    Resolver.jcenterRepo
  )
)

lazy val core = (project in file("core")).
  settings(
    commonSettings
  )

lazy val cli = (project in file("cli")).
  settings(
    commonSettings
  ).dependsOn(core)

lazy val server = (project in file("server")).
  settings(
    commonSettings
  ).dependsOn(core)
