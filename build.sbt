import Dependencies._

ThisBuild / scalaVersion := "2.13.7"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Xfatal-warnings",
  "-Xsource:3"
)
ThisBuild / autoAPIMappings := true

lazy val root =
  (project in file("."))
    .settings(
      name := "akka-coordinator-avoidance-shard-allocation-strategy",
      libraryDependencies ++= Seq(
        Akka.clusterSharding % Provided,
        Akka.clusterShardingTyped % Test,
        Akka.testKitTyped % Test,
        ScalaTest.scalaTest % Test,
        Logback.classic % Test
      )
    )

addCommandAlias(
  "ci",
  Seq("clean", "scalafmtSbtCheck", "scalafmtCheckAll", "Test/compile", "test")
    .mkString(";")
)

addCommandAlias(
  "testCoverage",
  Seq(
    "clean",
    "coverage",
    "test",
    "coverageReport"
  ).mkString(";")
)
