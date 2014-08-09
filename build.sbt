name := "parent"

Settings.common ++ Settings.disablePublishing

lazy val parent = project in file(".") aggregate(dust)

lazy val dust = project in file("plugins/dust") enablePlugins(SbtWeb)
