name := "dust-plugin"

sbtPlugin := true

Settings.common

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.0")

libraryDependencies ++= Seq(
  "org.webjars" % "dustjs-linkedin" % "2.4.0-1"
)
