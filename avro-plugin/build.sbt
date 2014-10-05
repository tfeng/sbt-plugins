import me.tfeng.sbt.plugins._

name := "avro-plugin"

sbtPlugin := true

Settings.common

libraryDependencies ++= Seq(
  "com.google.guava" % "guava" % Versions.guava,
  "org.apache.avro" % "avro-compiler" % Versions.avro
)

unmanagedSourceDirectories in Compile += baseDirectory.value / "../project"
