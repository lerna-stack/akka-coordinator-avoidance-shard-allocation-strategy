package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.{ActorRef, Address, RootActorPath}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.{Member, UniqueAddress}
import akka.internal
import akka.internal.DummyActorRef
import akka.util.Version

import scala.collection.immutable.SortedSet

object ShardAllocationStrategyTestKit {

  private val FakeActorRef =
    new DummyActorRef(
      RootActorPath(Address("akka", "myapp")) / "system" / "fake"
    )

  def newUpMember(
      host: String,
      port: Int = 252525,
      upNumber: Int = Int.MaxValue,
      version: Version = Version("1.0.0")
  ): Member = {
    internal
      .Member(
        UniqueAddress(Address("akka", "myapp", host, port), 1L),
        version
      )
      .copyUp(upNumber)
  }

  def newJoiningMember(
      host: String,
      port: Int = 252525,
      version: Version = Version("1.0.0")
  ): Member = {
    internal
      .Member(
        UniqueAddress(Address("akka", "myapp", host, port), 1L),
        version
      )
  }

  def newFakeRegion(id: String, member: Member): ActorRef = {
    new DummyActorRef(RootActorPath(member.address) / "system" / "fake" / id)
  }

  def newShards(from: Int, until: Int, numOfDigits: Int): Vector[ShardId] = {
    import math.*
    require(from >= 0)
    require(until >= 0)
    require(numOfDigits >= 1)
    require(from.toString.length <= numOfDigits)
    require(max(from, until - 1).toString.length <= numOfDigits)
    Vector
      .range(from, until)
      .map(n => ("0" * (numOfDigits - 1) + n.toString).takeRight(numOfDigits))
  }

  def newAllocations(
      countPerRegion: Map[ActorRef, Int],
      newShards: (ActorRef, Int, Int) => IndexedSeq[ShardId]
  )(implicit
      keyOrdering: Ordering[ActorRef]
  ): Map[ActorRef, IndexedSeq[ShardId]] = {
    val sortedKeys = countPerRegion.keys.toVector.sorted
    val (_, allocations) =
      sortedKeys.foldLeft((1, Map.empty[ActorRef, IndexedSeq[ShardId]])) {
        case ((from, allocations), region) =>
          val until = from + countPerRegion(region)
          val shards = newShards(region, from, until)
          val newAllocations = allocations + (region -> shards)
          (until, newAllocations)
      }
    assert(
      SortedSet
        .from(allocations.values.flatten)
        .size == countPerRegion.values.sum
    )
    sortedKeys.foreach { region =>
      assert(allocations(region).size == countPerRegion(region))
    }
    allocations
  }

  def newAllocations(
      countsPerRegion: IndexedSeq[Int],
      availableRegions: IndexedSeq[ActorRef],
      availableShards: IndexedSeq[ShardId]
  ): Map[ActorRef, IndexedSeq[String]] = {
    require(countsPerRegion.size <= availableRegions.size)
    require(countsPerRegion.forall(_ >= 0))
    require(countsPerRegion.sum <= availableShards.size)
    Vector
      .unfold((0, 0)) { case (i, from) =>
        if (i < countsPerRegion.size) {
          val until = from + countsPerRegion(i)
          val entry =
            availableRegions(i) -> availableShards.slice(from, until)
          Some((entry, (i + 1, from + countsPerRegion(i))))
        } else {
          None
        }
      }
      .toMap
  }

  def countShardsPerRegion(
      allocations: Map[ActorRef, IndexedSeq[ShardId]]
  )(implicit keyOrdering: Ordering[ActorRef]): Vector[Int] = {
    case class Entry(region: ActorRef, shardIds: IndexedSeq[ShardId])
    allocations
      .map({ case (region, shardIds) =>
        Entry(region, shardIds)
      })
      .toVector
      .sortBy(_.region)
      .map(entry => entry.shardIds.size)
  }

  def countShards(allocations: Map[ActorRef, IndexedSeq[ShardId]]): Int = {
    countShardsPerRegion(allocations).sum
  }

  def allocationsAfterRebalance(
      allocationStrategy: ShardAllocationStrategy,
      allocations: Map[ActorRef, IndexedSeq[ShardId]],
      shardsToBeRebalanced: Set[ShardId]
  ): Map[ActorRef, IndexedSeq[ShardId]] = {
    val allocationsAfterRemoval = allocations.map { case (region, shards) =>
      region -> shards.filterNot(shardsToBeRebalanced)
    }
    shardsToBeRebalanced.toList.sorted.foldLeft(allocationsAfterRemoval) {
      case (acc, shard) =>
        val region = allocationStrategy
          .allocateShard(FakeActorRef, shard, acc)
          .value
          .get
          .get
        acc.updated(region, acc(region) :+ shard)
    }
  }

  def allocationCountsAfterRebalance(
      allocationStrategy: ShardAllocationStrategy,
      allocations: Map[ActorRef, IndexedSeq[ShardId]],
      shardsToBeRebalanced: Set[ShardId]
  )(implicit keyOrdering: Ordering[ActorRef]): Vector[Int] = {
    val newAllocation = allocationsAfterRebalance(
      allocationStrategy,
      allocations,
      shardsToBeRebalanced
    )
    countShardsPerRegion(newAllocation)
  }

}
