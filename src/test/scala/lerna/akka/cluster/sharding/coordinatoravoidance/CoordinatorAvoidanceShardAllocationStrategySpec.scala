package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.{Member, MemberStatus}
import akka.cluster.sharding.ShardRegion.ShardId
import akka.util.Version
import com.typesafe.config.ConfigFactory

import scala.collection.immutable.SortedSet

object CoordinatorAvoidanceShardAllocationStrategySpec {

  def newFakeClusterStateProvider(
      system: ActorSystem,
      members: Iterable[Member],
      selfMember: Member
  ): CoordinatorAvoidanceShardAllocationStrategy.ClusterStateProvider = {
    val clusterState = CurrentClusterState(SortedSet.from(members))
    newFakeClusterStateProvider(system, clusterState, selfMember)
  }

  def newFakeClusterStateProvider(
      system: ActorSystem,
      clusterState: CurrentClusterState,
      selfMember: Member
  ): CoordinatorAvoidanceShardAllocationStrategy.ClusterStateProvider = {
    require(clusterState.members.contains(selfMember))
    val _system = system
    val _clusterState = clusterState
    val _selfMember = selfMember
    new CoordinatorAvoidanceShardAllocationStrategy.ClusterStateProvider {
      override def system: ActorSystem = _system
      override def clusterState: CurrentClusterState = _clusterState
      override def selfMember: Member = _selfMember
      override def start(): Unit = {}
    }
  }

  def newStrategy(
      clusterStateProvider: CoordinatorAvoidanceShardAllocationStrategy.ClusterStateProvider,
      absoluteLimit: Int = 0,
      relativeLimit: Double = 1.0,
      oldestNodesExcluded: Int = 0,
      minCap: Int = 1
  ): CoordinatorAvoidanceShardAllocationStrategy = {
    // we don't really "start" it as we fake the cluster access
    new CoordinatorAvoidanceShardAllocationStrategy(
      clusterStateProvider,
      CoordinatorAvoidanceShardAllocationStrategy.Settings(
        absoluteLimit = absoluteLimit,
        relativeLimit = relativeLimit,
        oldestNodesExcluded = oldestNodesExcluded,
        minCap = minCap
      )
    )
  }

}

final class CoordinatorAvoidanceShardAllocationStrategySpec
    extends ShardAllocationStrategySpecBase(
      ActorSystem("coordinator-avoidance-shard-allocation-strategy-spec")
    ) {
  import CoordinatorAvoidanceShardAllocationStrategySpec.*
  import ShardAllocationStrategyTestKit.*

  // Prepare members&regions
  // Some of them are not used, but we prepared them for convenience.
  val MaxRegionCount: Int = 10
  val MaxShardCount: Int = 1000
  lazy val members: Vector[Member] = Vector.tabulate(MaxRegionCount) { i =>
    newUpMember(s"127.0.0.${i + 1}", upNumber = i + 1)
  }
  lazy val regions: Vector[ActorRef] = {
    val regionSuffixes = Vector.range('A', 'Z')
    require((1 to regionSuffixes.size).contains(MaxRegionCount))
    Vector.tabulate(MaxRegionCount) { i =>
      newFakeRegion("region" :+ regionSuffixes(i), members(i))
    }
  }
  lazy val shards: Vector[ShardId] = {
    require((1 to 1000).contains(MaxShardCount))
    newShards(from = 1, until = MaxShardCount, numOfDigits = 3).toVector
  }

  // Accessors
  lazy val regionA: ActorRef = regions(0)
  lazy val regionB: ActorRef = regions(1)
  lazy val regionC: ActorRef = regions(2)
  lazy val regionD: ActorRef = regions(3)
  lazy val regionE: ActorRef = regions(4)

  def newAllocations(counts: Int*): Map[ActorRef, IndexedSeq[String]] = {
    ShardAllocationStrategyTestKit.newAllocations(
      counts.toVector,
      regions,
      shards
    )
  }

  def newStrategyWithFakeCluster(
      numOfMembers: Int,
      absoluteLimit: Int = 0,
      relativeLimit: Double = 1.0,
      oldestNodesExcluded: Int = 0,
      minCap: Int = 1
  ): CoordinatorAvoidanceShardAllocationStrategy = {
    require((1 to MaxRegionCount).contains(numOfMembers))
    // we don't really "start" it as we fake the cluster access
    val fakeClusterStateProvider = newFakeClusterStateProvider(
      system,
      members.take(numOfMembers),
      members(0)
    )
    new CoordinatorAvoidanceShardAllocationStrategy(
      fakeClusterStateProvider,
      CoordinatorAvoidanceShardAllocationStrategy.Settings(
        absoluteLimit = absoluteLimit,
        relativeLimit = relativeLimit,
        oldestNodesExcluded = oldestNodesExcluded,
        minCap = minCap
      )
    )
  }

  "CoordinatorAvoidanceShardAllocationStrategy" should {

    // allocate
    // no limits (oldestNodesExcluded = 0, minCap = 1)

    "allocate a shard to the region. shard: [0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(1)
      val allocations = newAllocations(0)
      allocationStrategy
        .allocateShard(regionA, "001", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. shard: [0, 1]" in {
      val allocationStrategy = newStrategyWithFakeCluster(2)
      val allocations = newAllocations(0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. shards: [1, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(2)
      val allocations = newAllocations(1, 0)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionB)
    }

    // allocate
    // oldestNodesExcluded = 1, minCap = 1

    "allocate a shard to the oldest region when there is only one node. oldestNodesExcluded: 1, minCap: 1, shards: [1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 1,
          oldestNodesExcluded = 1,
          minCap = 1
        )
      val allocations = newAllocations(1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "not allocate a shard to the oldest region. oldestNodesExcluded: 1, minCap: 1, shards: [0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 1,
          minCap = 1
        )
      val allocations = newAllocations(0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should !==(regionA)
    }

    "not allocate a shard to the oldest region. oldestNodesExcluded: 1, minCap: 1, shards: [0, 1, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 1
        )
      val allocations = newAllocations(0, 1, 1)
      allocationStrategy
        .allocateShard(regionA, "003", allocations)
        .futureValue should !==(regionA)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 1, minCap: 1, shards: [0, 1, 2]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 1
        )
      val allocations = newAllocations(0, 1, 2)
      allocationStrategy
        .allocateShard(regionA, "004", allocations)
        .futureValue should ===(regionB)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 1, minCap: 1, shards: [0, 2, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 1
        )
      val allocations = newAllocations(0, 2, 1)
      allocationStrategy
        .allocateShard(regionA, "004", allocations)
        .futureValue should ===(regionC)
    }

    // allocate
    // oldNodeExcluded = 1, minCap = 2

    "allocate a shard to the oldest region when there is only one node. oldestNodesExcluded: 1, minCap: 2, shards: [1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 1,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. oldestNodesExcluded: 1, minCap: 2, shards: [0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. oldestNodesExcluded: 1, minCap: 2, shards: [1, 0]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(1, 0)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionB)
    }

    "not allocate a shard to the oldest region. oldestNodesExcluded: 1, minCap: 2, shards: [0, 1, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(0, 1, 1)
      allocationStrategy
        .allocateShard(regionA, "003", allocations)
        .futureValue should !==(regionA)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 1, minCap: 2, shards: [0, 1, 2]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(0, 1, 2)
      allocationStrategy
        .allocateShard(regionA, "004", allocations)
        .futureValue should ===(regionB)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 1, minCap: 2, shards: [0, 2, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 1,
          minCap = 2
        )
      val allocations = newAllocations(0, 2, 1)
      allocationStrategy
        .allocateShard(regionA, "004", allocations)
        .futureValue should ===(regionC)
    }

    // allocate
    // oldestNodesExcluded = 2, minCap = 1

    "allocate a shard to the oldest node if there is only one node. oldestNodesExcluded: 2, minCap: 1, shards: [1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 1,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to the 2th oldest region if there is only two nodes. oldestNodesExcluded: 2, minCap: 1, shards: [0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionB)
    }

    "not allocate a shard to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 1, shards: [0, 0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 0, 1)
      val allocatedRegion = allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue
      allocatedRegion should !==(regionA)
      allocatedRegion should !==(regionB)
    }

    "not allocate a shard to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 1, [0, 0, 1, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 4,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 0, 1, 1)
      val allocatedRegion = allocationStrategy
        .allocateShard(regionA, "004", allocations)
        .futureValue
      allocatedRegion should !==(regionA)
      allocatedRegion should !==(regionB)
    }

    "allocate a shard to region with least number of shards but not the 2-oldest. oldestNodesExcluded: 2, minCap: 1, [0, 0, 1, 2, 2]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 5,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 0, 1, 2, 2)
      allocationStrategy
        .allocateShard(regionA, "006", allocations)
        .futureValue should ===(regionC)
    }

    "allocate a shard to region with least number of shards but not the 2-oldest. oldestNodesExcluded: 2, minCap: 1, [0, 0, 2, 1, 2]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 5,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 0, 2, 1, 2)
      allocationStrategy
        .allocateShard(regionA, "006", allocations)
        .futureValue should ===(regionD)
    }

    "allocate a shard to region with least number of shards but not the 2-oldest. oldestNodesExcluded: 2, minCap: 1, [0, 0, 2, 2, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 5,
          oldestNodesExcluded = 2,
          minCap = 1
        )
      val allocations = newAllocations(0, 0, 2, 2, 1)
      allocationStrategy
        .allocateShard(regionA, "006", allocations)
        .futureValue should ===(regionE)
    }

    // allocate
    // oldestNodesExcluded = 2, minCap = 2

    "allocate a shard to the oldest region if there is only one shard. oldestNodesExcluded: 2, minCap: 2, shards: [1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 1,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. oldestNodesExcluded: 2, minCap: 2, shards: [0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionA)
    }

    "allocate a shard to region with least number of shards. oldestNodesExcluded: 2, minCap: 2, shards: [1, 0]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 2,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(1, 0)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionB)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 2, minCap: 2, shards: [0, 0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionB)
    }

    "allocate a shard to region with least number of shards but not the oldest. oldestNodesExcluded: 2, minCap: 2, shards: [0, 1, 0]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 1, 0)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionC)
    }

    "not allocate a shard to the oldest region if there is only three nodes. oldestNodesExcluded: 2, minCap: 2, shards: [0, 1, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 1, 1)
      allocationStrategy
        .allocateShard(regionA, "003", allocations)
        .futureValue should !==(regionA)
    }

    "allocate a shard to region with least number of shards but not the 2-oldest. oldestNodesExcluded: 2, minCap: 2, shards: [0, 0, 0, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 4,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 0, 0, 1)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionC)
    }

    "allocate a shard to region with least number of shards but not the 2-oldest. oldestNodesExcluded: 2, minCap: 2, shards: [0, 0, 1, 0]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 4,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 0, 1, 0)
      allocationStrategy
        .allocateShard(regionA, "002", allocations)
        .futureValue should ===(regionD)
    }

    "not allocate a shard to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 2, shards: [0, 0, 1, 1]" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 4,
          oldestNodesExcluded = 2,
          minCap = 2
        )
      val allocations = newAllocations(0, 0, 1, 1)
      val allocatedRegion = allocationStrategy
        .allocateShard(regionA, "003", allocations)
        .futureValue
      allocatedRegion should !==(regionA)
      allocatedRegion should !==(regionB)
    }

    // rebalance
    // no limits (oldestNodesExcluded = 0, minCap = 1)

    "rebalance shards [1, 2, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(1, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(1, 1, 1))
    }

    "rebalance shards [2, 0, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(2, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(1, 1, 0))
    }

    "not rebalance shards [1, 1, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(1, 1, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [3, 0, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(3, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(1, 1, 1))
    }

    "rebalance shards [4, 4, 0]" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(4, 4, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(3, 3, 2))
    }

    "rebalance shards [5, 5, 0]" in {
      // this is handled by phase 1
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(5, 5, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "006"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(4, 4, 2))
    }

    "rebalance shards [4, 4, 2]" in {
      // this is handled by phase 2, to find diff of 2
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(4, 4, 2)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(3, 4, 3))
    }

    // rebalance
    // oldestNodesExcluded = 1, minCap = 1

    "not rebalance shards [1] if there is only the oldest region. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(1, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(1)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [1, 0] to avoid allocate shards to the oldest region. oldestNodesExcluded: 1, minCap: 1" in {
      // This scenario could be possible.
      // Suppose a period in which we have only one node (such as in a startup or after nodes shut down).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1))
    }

    "not rebalance shards [0, 2]. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 2)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [0, 2, 0]. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1))
    }

    "rebalance shards [0, 2, 0, 0]. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 2, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1, 0))
    }

    "rebalance shards [0, 3, 0, 0]. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 3, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1, 1))
    }

    "rebalance shards [0, 4, 4, 0]. oldestNodesExcluded: 1, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 4, 4, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 3, 3, 2))
    }

    "rebalance shards [0, 5, 5, 0]. oldestNodesExcluded: 1, minCap: 1" in {
      // this is handled by phase 1
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 5, 5, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "006"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 4, 4, 2))
    }

    "rebalance shards [0, 4, 4, 2]. oldestNodesExcluded: 1, minCap: 1" in {
      // this is handled by phase 2, to find diff of 2
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 4, 4, 2)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 3, 4, 3))
    }

    // rebalance
    // oldestNodesExcluded = 1, minCap = 2

    "not rebalance shards [1] if there is only the oldest region. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(1, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(1)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [2, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(1, 1))
    }

    "rebalance shards [1, 1, 0] to avoid allocate shards to the oldest region. oldestNodesExcluded: 1, minCap: 2" in {
      // This scenario could be possible.
      // Suppose a period in which we have only two node (such as in a startup or after nodes shut down).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(1, 1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1))
    }

    "not rebalance shards [0, 2, 2]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 2, 2)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [0, 2, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1))
    }

    "rebalance shards [0, 3, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 3, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 2, 1))
    }

    "rebalance shards [0, 3, 0, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 3, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1, 1))
    }

    "rebalance shards [0, 4, 4, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 4, 4, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 3, 3, 2))
    }

    "rebalance shards [0, 5, 5, 0]. oldestNodesExcluded: 1, minCap: 2" in {
      // this is handled by phase 1
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 5, 5, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "006"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 4, 4, 2))
    }

    "rebalance shards [0, 4, 4, 2]. oldestNodesExcluded: 1, minCap: 2" in {
      // this is handled by phase 2, to find diff of 2
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 1, minCap = 2)
      val allocations = newAllocations(0, 4, 4, 2)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 3, 4, 3))
    }

    // rebalance
    // oldestNodesExcluded = 2, minCap = 1

    "not rebalance shards [1] if there is only the oldest region. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(1, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(1)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [1, 0] to avoid allocate shards to the oldest region. oldestNodesExcluded: 2, minCap: 1" in {
      // This scenario could be possible.
      // Suppose a period in which we have only one node (such as in a startup or after nodes shut down).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1))
    }

    "not rebalance shards [0, 2] if there is only two regions. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 2)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [1, 1, 0] to avoid allocate shards to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 1" in {
      // This scenario could be possible.
      // Suppose the below scenario.
      // (1) We have four nodes; The shard allocations is [0, 0, 1, 1].
      // (2) Both the 1st node and the 2nd node crashes; The shard allocations is [1, 1].
      // (3) Add a node before rebalance occurs; The shard allocations is [1, 1, 0].
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(1, 1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 2))
    }

    "rebalance shards [0, 2, 0] to avoid allocate shards to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 1" in {
      // This scenario could be possible.
      // Suppose a period in which we have only two node (rebalance was completed).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 2))
    }

    "rebalance shards [0, 0, 1, 2, 0]. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 0, 1, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 1))
    }

    "rebalance shards [0, 0, 2, 0, 0]. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 0, 2, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 0))
    }

    "not rebalance shards [0, 0, 1, 1, 0]. oldestNodesExcluded: 2, minCap:1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 1, minCap = 1)
      val allocations = newAllocations(0, 0, 1, 1, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [0, 0, 3, 0, 0]. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 0, 3, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 1))
    }

    "rebalance shards [0, 0, 4, 4, 0]. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 0, 4, 4, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 3, 3, 2))
    }

    "rebalance shards [0, 0, 4, 4, 2]. oldestNodesExcluded: 2, minCap: 1" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 1)
      val allocations = newAllocations(0, 0, 4, 4, 2)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 3, 4, 3))
    }

    // rebalance
    // oldestNodesExcluded = 2, minCap = 2

    "not rebalance shards [1] if there is only the oldest region. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(1, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(1)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "rebalance shards [2, 0] if there is only two regions. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(2, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(1, 1))
    }

    "rebalance shards [1, 1, 0] to avoid allocate shards to the oldest region. oldestNodesExcluded: 2, minCap: 2" in {
      // This scenario could be possible.
      // Suppose a period in which we have only two node (such as in a startup or after nodes shut down).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(1, 1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 1, 1))
    }

    "rebalance shards [0, 3, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      // This scenario could be possible.
      // Suppose the below scenario.
      // (1) We have four nodes; The shard allocations is [0, 0, 3, 3].
      // (2) Both the 2nd node and the 3rd node crashes; The shard allocations is [0, 3].
      // (3) Add a node before rebalance occurs; The shard allocations is [0, 3, 0].
      //   Suppose there is no request that requires us to allocate a new shard until we add the node.
      val allocationStrategy =
        newStrategyWithFakeCluster(3, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 3, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 2, 1))
    }

    "rebalance shards [0, 1, 1, 0] to avoid allocate shards to the 2-oldest regions. oldestNodesExcluded: 2, minCap: 2" in {
      // This scenario could be possible.
      // Suppose a period in which we have only three node (such as in a startup or after nodes shut down).
      // Once we add a new node to the cluster, a rebalance, like this scenario, happens.
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 1, 1, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1))
    }

    "rebalance shards [0, 0, 3, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(4, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 3, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 2, 1))
    }

    "rebalance shards [0, 0, 1, 2, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 1, 2, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 1))
    }

    "rebalance shards [0, 0, 2, 0, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 2, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 0))
    }

    "rebalance shards [0, 0, 3, 0, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 3, 0, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 1, 1, 1))
    }

    "rebalance shards [0, 0, 4, 4, 0]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 4, 4, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 3, 3, 2))
    }

    "rebalance shards [0, 0, 4, 4, 2]. oldestNodesExcluded: 2, minCap: 2" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(5, oldestNodesExcluded = 2, minCap = 2)
      val allocations = newAllocations(0, 0, 4, 4, 2)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(0, 0, 3, 4, 3))
    }

    // rebalance
    // absolute limit, relative limit

    "respect absolute limit of number shards" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          absoluteLimit = 3,
          relativeLimit = 1.0
        )
      val allocations = newAllocations(1, 9, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002", "003", "004"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(2, 6, 2))
    }

    "respect relative limit of number shards" in {
      val allocationStrategy =
        newStrategyWithFakeCluster(
          numOfMembers = 3,
          absoluteLimit = 5,
          relativeLimit = 0.3
        )
      val allocations = newAllocations(1, 9, 0)
      val result =
        allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002", "003", "004"))
      allocationCountsAfterRebalance(
        allocationStrategy,
        allocations,
        result
      ) should ===(Vector(2, 6, 2))
    }

    // rebalance
    // scenarios in which we should not rebalance
    // (not related to rebalance settings)

    "not rebalance when in progress" in {
      val allocationStrategy = newStrategyWithFakeCluster(3)
      val allocations = newAllocations(10, 0, 0)
      allocationStrategy
        .rebalance(allocations, Set("002", "003"))
        .futureValue should ===(Set.empty[String])
    }

    // TODO We have some extra work to support the Version since the Version is the internal API.
    "not rebalance when rolling update in progress" ignore {
      val allocationStrategy = {
        // multiple versions to simulate rolling update in progress
        val member1 = newUpMember("127.0.0.1", version = Version("1.0.0"))
        val member2 = newUpMember("127.0.0.1", version = Version("1.0.1"))
        newStrategy(
          newFakeClusterStateProvider(
            system,
            members = Vector(member1, member2),
            selfMember = member1
          )
        )
      }
      val allocations = newAllocations(5, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "not rebalance when regions are unreachable" in {
      val allocationStrategy = {
        val member1 = newUpMember("127.0.0.1")
        val member2 = newUpMember("127.0.0.2")
        val clusterState = CurrentClusterState(
          SortedSet(member1, member2),
          unreachable = Set(member2)
        )
        newStrategy(
          newFakeClusterStateProvider(
            system,
            clusterState = clusterState,
            selfMember = member1
          )
        )
      }
      val allocations = newAllocations(5, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "not rebalance when members are joining" in {
      val allocationStrategy = {
        val member1 = newUpMember("127.0.0.1")
        val member2 = newJoiningMember("127.0.0.2")
        val clusterState = CurrentClusterState(SortedSet(member1, member2))
        newStrategy(
          newFakeClusterStateProvider(
            system,
            clusterState = clusterState,
            selfMember = member1
          )
        )
      }
      val allocations = newAllocations(5, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

    "not rebalance when members are leaving" in {
      val allocationStrategy = {
        val member1 = newUpMember("127.0.0.1").copy(MemberStatus.Leaving)
        val member2 = newUpMember("127.0.0.2")
        val member3 = newUpMember("127.0.0.3")
        val clusterState =
          CurrentClusterState(SortedSet(member1, member2, member3))
        newStrategy(
          newFakeClusterStateProvider(
            system,
            clusterState = clusterState,
            selfMember = member1
          )
        )
      }
      val allocations = newAllocations(5, 5, 0)
      allocationStrategy
        .rebalance(allocations, Set.empty)
        .futureValue should ===(Set.empty[String])
    }

  }

  "CoordinatorAvoidanceShardAllocationStrategy.Settings" should {

    "load the default setting from the given ActorSystem" in {

      val settings =
        CoordinatorAvoidanceShardAllocationStrategy.Settings(system)
      settings.absoluteLimit should ===(0)
      settings.relativeLimit should ===(0.1)
      settings.oldestNodesExcluded should ===(1)
      settings.minCap should ===(1)

    }

    "load the custom settings from the config" in {

      val config = ConfigFactory.parseString(
        """
          |{
          |  oldest-nodes-excluded-from-allocation = 2
          |  allocation-nodes-min-cap = 3
          |  rebalance-absolute-limit = 30
          |  rebalance-relative-limit = 0.2
          |}
          |""".stripMargin
      )
      val settings =
        CoordinatorAvoidanceShardAllocationStrategy.Settings(config)
      settings.absoluteLimit should ===(30)
      settings.relativeLimit should ===(0.2)
      settings.oldestNodesExcluded should ===(2)
      settings.minCap should ===(3)

    }

    "throw IllegalArgumentException if given absoluteLimit is out of range" in {

      a[IllegalArgumentException] should be thrownBy {
        CoordinatorAvoidanceShardAllocationStrategy.Settings(absoluteLimit = -1)
      }

    }

    "throw IllegalArgumentException if given relativeLimit is out of range" in {

      a[IllegalArgumentException] should be thrownBy {
        CoordinatorAvoidanceShardAllocationStrategy.Settings(relativeLimit =
          -0.1
        )
      }

      a[IllegalArgumentException] should be thrownBy {
        CoordinatorAvoidanceShardAllocationStrategy.Settings(relativeLimit =
          1.1
        )
      }

    }

    "throw IllegalArgumentException if given oldNodesExcluded is out of range" in {

      a[IllegalArgumentException] should be thrownBy {
        CoordinatorAvoidanceShardAllocationStrategy
          .Settings(oldestNodesExcluded = -1)
      }

    }

    "throw IllegalArgumentException if given minCap is out of range" in {

      a[IllegalArgumentException] should be thrownBy {
        CoordinatorAvoidanceShardAllocationStrategy.Settings(minCap = 0)
      }

    }

  }

}
