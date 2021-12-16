package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.testkit.{ImplicitSender, TestKit, TestKitExtension}
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.{BeforeAndAfterAll, Inside}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

abstract class ShardAllocationStrategySpecBase(system: ActorSystem)
    extends TestKit(system)
    with AnyWordSpecLike
    with Matchers
    with Inside
    with BeforeAndAfterAll
    with ScalaFutures
    with ImplicitSender
    with LogCapturing
    with TypeCheckedTripleEquals {

  implicit override lazy val patienceConfig: PatienceConfig = {
    val testKitSettings = TestKitExtension(system)
    PatienceConfig(
      scaled(testKitSettings.DefaultTimeout.duration),
      scaled(Span(100, org.scalatest.time.Millis))
    )
  }

  override lazy val spanScaleFactor: Double = {
    TestKitExtension.get(system).TestTimeFactor
  }

  override def afterAll(): Unit = {
    try shutdown(system)
    finally super.afterAll()
  }

}
