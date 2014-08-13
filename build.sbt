import me.tfeng.sbt.plugins._

name := "parent"

Settings.common ++ Settings.disablePublishing

lazy val parent = project in file(".") aggregate(avro, dust)

lazy val avro = project in file("plugins/avro")

lazy val dust = project in file("plugins/dust") enablePlugins(SbtWeb)
