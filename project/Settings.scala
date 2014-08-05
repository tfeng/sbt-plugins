import com.typesafe.sbt.pgp._
import com.typesafe.sbteclipse.plugin.EclipsePlugin._
import sbt._
import Keys._

object Settings {
  val common: Seq[Setting[_]] = Seq(
    organization := "me.tfeng.sbt-plugins",
    version := "0.1.1-SNAPSHOT",
    crossPaths := true
  )

  val disablePublishing: Seq[Setting[_]] = Seq(
    publishArtifact := false,
    publish := (),
    publishLocal := (),
    publishM2 := (),
    PgpKeys.publishSigned := (),
    PgpKeys.publishLocalSigned := ()
  )
}
