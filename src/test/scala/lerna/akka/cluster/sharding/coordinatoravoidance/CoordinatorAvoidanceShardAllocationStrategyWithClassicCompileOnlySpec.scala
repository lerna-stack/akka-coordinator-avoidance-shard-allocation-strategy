package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{
  ClusterSharding,
  ClusterShardingSettings,
  ShardRegion
}

/** Shows how to use [[CoordinatorAvoidanceShardAllocationStrategy]] with
  * Classic Akka Cluster Sharding
  *
  * This class only verifies the following code compiles.
  */
final class CoordinatorAvoidanceShardAllocationStrategyWithClassicCompileOnlySpec {

  val system: ActorSystem = ???
  val typeName: String = ???
  val entityProps: Props = ???
  val settings: ClusterShardingSettings = ???
  val messageExtractor: ShardRegion.MessageExtractor = ???
  val handOffStopMessage: Any = ???

  val shardRegion: ActorRef = ClusterSharding(system).start(
    typeName,
    entityProps,
    settings,
    messageExtractor,
    CoordinatorAvoidanceShardAllocationStrategy(system),
    handOffStopMessage
  )

}
