import me.tfeng.sbt.plugins._

name := "avro-plugin"

sbtPlugin := true

Settings.common

libraryDependencies += "org.apache.avro" % "avro-compiler" % Versions.avro

unmanagedSourceDirectories in Compile += baseDirectory.value / "../project"
