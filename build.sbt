name := "parent"

Settings.common ++ Settings.disablePublishing

lazy val parent = project in file(".") aggregate(dust)

lazy val dust = project in file("plugins/dust") enablePlugins(SbtWeb)

pomExtra :=
  <developers>
    <developer>
      <email>tfeng@berkeley.edu</email>
      <name>Thomas Feng</name>
      <url>https://github.com/tfeng</url>
      <id>tfeng</id>
    </developer>
  </developers>
  <url>https://github.com/tfeng/sbt-plugins</url>
  <scm>
    <url>https://github.com/tfeng/sbt-plugins</url>
    <connection>scm:git:https://github.com/tfeng/sbt-plugins.git</connection>
    <developerConnection>scm:git:git@github.com:tfeng/sbt-plugins.git</developerConnection>
  </scm>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
