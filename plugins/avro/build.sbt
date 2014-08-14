import me.tfeng.sbt.plugins._

name := "avro-plugin"

sbtPlugin := true

Settings.common

libraryDependencies += "org.apache.avro" % "avro-compiler" % Versions.avro

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.5.0")

unmanagedSourceDirectories in Compile += baseDirectory.value / "../../project"
