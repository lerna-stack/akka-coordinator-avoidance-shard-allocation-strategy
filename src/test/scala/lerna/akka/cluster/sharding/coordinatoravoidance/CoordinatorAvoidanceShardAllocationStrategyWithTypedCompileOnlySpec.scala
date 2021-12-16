package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityContext,
  EntityTypeKey
}

/** Shows how to use [[CoordinatorAvoidanceShardAllocationStrategy]] with Typed
  * Akka Cluster Sharding
  *
  * This class only verifies the following code compiles.
  */
final class CoordinatorAvoidanceShardAllocationStrategyWithTypedCompileOnlySpec {

  trait Command
  val system: ActorSystem[?] = ???
  val typeKey: EntityTypeKey[Command] = ???
  val createBehavior: EntityContext[Command] => Behavior[Command] = ???

  val entity: Entity[Command, ShardingEnvelope[Command]] =
    Entity(typeKey)(createBehavior)
      .withAllocationStrategy(
        CoordinatorAvoidanceShardAllocationStrategy(system)
      )
  val shardRegion: ActorRef[ShardingEnvelope[Command]] =
    ClusterSharding(system).init(entity)

}
