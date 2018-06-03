val sttpVersion = "1.2.0-RC2"

lazy val commonSettings = Seq(
  name := "sttp-vertx",
  organization := "com.github.guymers",
  licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt"))),
  scalaVersion := "2.12.6",

  // https://tpolecat.github.io/2017/04/25/scalac-flags.html
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-explaintypes",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-unchecked",
    "-Xcheckinit",
//    "-Xfatal-warnings",
    "-Xfuture",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ypartial-unification",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-value-discard"
  ),
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, minor)) if minor >= 12 => Seq("-Ywarn-extra-implicit")
    case _ => Seq.empty
  }),

  scalacOptions in (Compile, console) --= Seq(
    "-Xfatal-warnings",
    "-Ywarn-unused"
  ),

  dependencyOverrides += "org.scala-lang" % "scala-library" % scalaVersion.value,
  dependencyOverrides += "org.scala-lang" % "scala-reflect" % scalaVersion.value,

  fork := true
)

lazy val `sttp-vertx` = project.in(file("."))
  .settings(commonSettings)
  .settings(Seq(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp" %% "core" % sttpVersion,
      "com.softwaremill.sttp" %% "monix" % sttpVersion,
      "io.vertx" % "vertx-core" % "3.5.1",

      "com.softwaremill.sttp" %% "core" % sttpVersion % "test" classifier "tests",
      "com.softwaremill.sttp" %% "monix" % sttpVersion % "test" classifier "tests",
      "com.typesafe.akka" %% "akka-http" % "10.1.1" % "test",
      "ch.megard" %% "akka-http-cors" % "0.3.0" % "test",
      "com.typesafe.akka" %% "akka-stream" % "2.5.13" % "test",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    )
  ))
