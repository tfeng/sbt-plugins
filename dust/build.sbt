import me.tfeng.sbt.plugins._

name := "dust"

sbtPlugin := true

Settings.common

libraryDependencies ++= Seq(
  "org.webjars" % "webjars-locator" % Versions.webjarsLocator,
  "org.webjars" % "dustjs-linkedin" % Versions.dustjs
)
