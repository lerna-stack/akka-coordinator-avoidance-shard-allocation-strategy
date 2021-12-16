package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import CoordinatorAvoidanceShardAllocationStrategySpec.*

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.util.Random

final class CoordinatorAvoidanceShardAllocationStrategyRandomizedSpec
    extends ShardAllocationStrategySpecBase(
      ActorSystem(
        "coordinator-avoidance-shard-allocation-strategy-randomized-spec"
      )
    ) {

  import ShardAllocationStrategyTestKit.*

  private val randomSeed = System.currentTimeMillis()
  private val random = new Random(randomSeed)
  info(s"Random seed: $randomSeed")

  private var iteration = 1
  private val iterationsPerTest = 10

  private def testRebalance(
      strategyFactory: (CurrentClusterState, Member) => ShardAllocationStrategy,
      isBalanced: (
          Map[ActorRef, IndexedSeq[ShardId]],
          Map[ActorRef, Member]
      ) => Boolean,
      regionRange: Range,
      maxShardsPerRegion: Int,
      expectedMaxSteps: Int = 2
  ): Unit = {
    (1 to iterationsPerTest).foreach { _ =>
      iteration += 1
      val numberOfRegions =
        random.nextInt(regionRange.size) + regionRange.min
      val members =
        Vector.tabulate(numberOfRegions)(n =>
          newUpMember(s"127.0.0.${n + 1}", port = n + 1, upNumber = n + 1)
        )
      val regions =
        Vector.tabulate(numberOfRegions)(n =>
          newFakeRegion(s"$iteration-R${n + 1}", members(n))
        )
      val memberPerRegion = regions.zipWithIndex.map { case (region, index) =>
        region -> members(index)
      }.toMap
      val allocationStrategy =
        strategyFactory(
          CurrentClusterState(SortedSet.from(members)),
          members(0)
        )
      val countPerRegion = regions.map { region =>
        region -> random.nextInt(maxShardsPerRegion)
      }.toMap
      val allocations = ShardAllocationStrategyTestKit.newAllocations(
        countPerRegion,
        (region, from, until) => {
          val size = until - from
          newShards(0, size, 3).map(n => s"${region.path.name}-$n")
        }
      )
      withClue(
        s"test $allocationStrategy [${countShardsPerRegion(allocations).mkString(",")}]: "
      ) {
        testRebalance(
          expectedMaxSteps,
          isBalanced,
          allocationStrategy,
          memberPerRegion,
          allocations,
          Vector(allocations)
        )
      }
      regions.foreach(system.stop)
    }
  }

  @tailrec private def testRebalance(
      expectedMaxSteps: Int,
      isBalanced: (
          Map[ActorRef, IndexedSeq[ShardId]],
          Map[ActorRef, Member]
      ) => Boolean,
      allocationStrategy: ShardAllocationStrategy,
      memberPerRegion: Map[ActorRef, Member],
      allocations: Map[ActorRef, IndexedSeq[ShardId]],
      allocationHistory: Vector[Map[ActorRef, IndexedSeq[ShardId]]]
  ): Unit = {
    val round = allocationHistory.size
    val rebalanceResult =
      allocationStrategy.rebalance(allocations, Set.empty).value.get.get
    val newAllocations = allocationsAfterRebalance(
      allocationStrategy,
      allocations,
      rebalanceResult
    )

    countShards(newAllocations) should ===(countShards(allocations))
    val newAllocationsHistory = allocationHistory :+ newAllocations

    if (isBalanced(newAllocations, memberPerRegion)) {
      info(
        s"$allocationStrategy: rebalance solved in round $round, [${newAllocationsHistory
          .map(step => countShardsPerRegion(step).mkString(","))
          .mkString(" => ")}]"
      )
    } else if (round == expectedMaxSteps) {
      fail(
        s"Couldn't solve rebalance in $round rounds, [${newAllocationsHistory
          .map(step => countShardsPerRegion(step).mkString(","))
          .mkString(" => ")}]"
      )
    } else {
      testRebalance(
        expectedMaxSteps,
        isBalanced,
        allocationStrategy,
        memberPerRegion,
        newAllocations,
        newAllocationsHistory
      )
    }
  }

  private def isBalancedAll(
      allocations: Map[ActorRef, IndexedSeq[ShardId]],
      memberPerRegion: Map[ActorRef, Member]
  ): Boolean = {
    val counts = countShardsPerRegion(allocations)
    counts.max - counts.min <= 1
  }

  private def isBalancedExceptOldest(
      oldestNodesExcluded: Int,
      minCap: Int
  )(
      allocations: Map[ActorRef, IndexedSeq[ShardId]],
      memberPerRegion: Map[ActorRef, Member]
  ): Boolean = {
    val oldestRegions = memberPerRegion.toVector
      .sortBy(_._2)(Member.ageOrdering)
      .dropRight(minCap)
      .take(oldestNodesExcluded)
      .map(_._1)
      .toSet
    val (allocationsOfOldest, allocationsExceptOldest) = allocations
      .partition { case (region, _) => oldestRegions.contains(region) }
    val doesOldestRegionsHaveNoShards =
      countShards(allocationsOfOldest) == 0
    val isBalancedExceptOldestRegions = {
      val shards = countShardsPerRegion(allocationsExceptOldest)
      shards.max - shards.min <= 1
    }
    doesOldestRegionsHaveNoShards && isBalancedExceptOldestRegions
  }

  private def newStrategy(
      absoluteLimit: Int = 0,
      relativeLimit: Double = 1.0,
      oldestNodesExcluded: Int = 0,
      minCap: Int = 1
  )(
      clusterState: CurrentClusterState,
      selfMember: Member
  ): CoordinatorAvoidanceShardAllocationStrategy = {
    val clusterStateProvider =
      newFakeClusterStateProvider(system, clusterState, selfMember)
    val settings = CoordinatorAvoidanceShardAllocationStrategy.Settings(
      absoluteLimit = absoluteLimit,
      relativeLimit = relativeLimit,
      oldestNodesExcluded = oldestNodesExcluded,
      minCap = minCap
    )
    new CoordinatorAvoidanceShardAllocationStrategy(
      clusterStateProvider,
      settings
    )
  }

  "CoordinatorAvoidanceShardAllocationStrategy with random scenario" must {

    "rebalance shards with max 5 regions / 5 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 5,
        maxShardsPerRegion = 5
      )
    }

    "rebalance shards with max 5 regions / 100 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 5,
        maxShardsPerRegion = 100
      )
    }

    "rebalance shards with max 20 regions / 5 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 20,
        maxShardsPerRegion = 5
      )
    }

    "rebalance shards with max 20 regions / 20 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 20,
        maxShardsPerRegion = 20
      )
    }

    "rebalance shards with max 20 regions / 200 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 20,
        maxShardsPerRegion = 200
      )
    }

    "rebalance shards with max 100 regions / 100 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 100,
        maxShardsPerRegion = 100
      )
    }

    "rebalance shards with max 100 regions / 1000 shards" in {
      testRebalance(
        newStrategy(),
        isBalancedAll,
        regionRange = 1 to 100,
        maxShardsPerRegion = 1000
      )
    }

    "rebalance shards with max 20 regions / 20 shards and limits" in {
      val maxRegions = 20
      val maxShardsPerRegion = 20
      val absoluteLimit = 3 + random.nextInt(7) + 3
      val relativeLimit = 0.05 + (random.nextDouble() * 0.95)
      val maxShards = maxShardsPerRegion * maxRegions
      val maxShardsPerStep =
        math.min(absoluteLimit, relativeLimit * maxShards).toInt
      val maxSteps = {
        // round up
        maxShards / maxShardsPerStep +
          (if (maxShards % maxShardsPerStep == 0) 0 else 1)
      }
      testRebalance(
        newStrategy(
          absoluteLimit = absoluteLimit,
          relativeLimit = relativeLimit
        ),
        isBalancedAll,
        regionRange = 1 to maxRegions,
        maxShardsPerRegion = maxShardsPerRegion,
        expectedMaxSteps = maxSteps
      )
    }

    "rebalance shards with max 20 regions / 200 shards. oldestNodesExcluded: 1, minCap: 1" in {
      testRebalance(
        newStrategy(oldestNodesExcluded = 1, minCap = 1),
        isBalancedExceptOldest(oldestNodesExcluded = 1, minCap = 1),
        regionRange = 1 to 20,
        maxShardsPerRegion = 200
      )
    }

    "rebalance shards with max 20 regions / 200 shards. oldestNodesExcluded: 1, minCap: 2" in {
      testRebalance(
        newStrategy(oldestNodesExcluded = 1, minCap = 2),
        isBalancedExceptOldest(oldestNodesExcluded = 1, minCap = 2),
        regionRange = 1 to 20,
        maxShardsPerRegion = 200
      )
    }

    "rebalance shards with max 20 regions / 200 shards. oldestNodesExcluded: 2, minCap: 1" in {
      testRebalance(
        newStrategy(oldestNodesExcluded = 2, minCap = 1),
        isBalancedExceptOldest(oldestNodesExcluded = 2, minCap = 1),
        regionRange = 1 to 20,
        maxShardsPerRegion = 200
      )
    }

    "rebalance shards with max 20 regions / 200 shards. oldestNodesExcluded: 2, minCap: 2" in {
      testRebalance(
        newStrategy(oldestNodesExcluded = 2, minCap = 2),
        isBalancedExceptOldest(oldestNodesExcluded = 2, minCap = 2),
        regionRange = 1 to 20,
        maxShardsPerRegion = 200
      )
    }

  }

}
