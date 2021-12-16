import sbt._

object Dependencies {

  object Versions {
    val Akka = "2.6.16"
    val ScalaTest = "3.2.10"
    val Logback = "1.2.7"
  }

  object Akka {
    val clusterSharding =
      "com.typesafe.akka" %% "akka-cluster-sharding" % Versions.Akka
    val clusterShardingTyped =
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Versions.Akka
    val testKitTyped =
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % Versions.Akka
  }

  object ScalaTest {
    val scalaTest = "org.scalatest" %% "scalatest" % Versions.ScalaTest
  }

  object Logback {
    val classic = "ch.qos.logback" % "logback-classic" % Versions.Logback
  }

}
