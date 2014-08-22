import me.tfeng.sbt.plugins._

name := "parent"

Settings.common ++ Settings.disablePublishing

lazy val parent = project in file(".") aggregate(avro, dust)

lazy val avro = project in file("avro-plugin")

lazy val dust = project in file("dust-plugin") enablePlugins(SbtWeb)
