import me.tfeng.sbt.plugins._

name := "dust-plugin"

sbtPlugin := true

Settings.common

addSbtPlugin("com.typesafe.sbt" % "sbt-web" % Versions.sbtWeb)

libraryDependencies += "org.webjars" % "dustjs-linkedin" % Versions.dustjs
