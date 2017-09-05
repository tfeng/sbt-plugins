import me.tfeng.sbt.plugins._

name := "parent"

Settings.common ++ Settings.disablePublishing

lazy val parent = project in file(".") aggregate(avro)

lazy val avro = project
