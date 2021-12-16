import Dependencies._

ThisBuild / scalaVersion := "2.13.7"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Xsource:3"
)
ThisBuild / scalacOptions ++= sys.props
  .get("lerna.enable.discipline")
  .map(_ => "-Xfatal-warnings")
  .toSeq
ThisBuild / autoAPIMappings := true

// Minimum Coverage
ThisBuild / coverageFailOnMinimum := true
ThisBuild / coverageMinimumStmtTotal := 80
ThisBuild / coverageMinimumBranchTotal := 99

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
