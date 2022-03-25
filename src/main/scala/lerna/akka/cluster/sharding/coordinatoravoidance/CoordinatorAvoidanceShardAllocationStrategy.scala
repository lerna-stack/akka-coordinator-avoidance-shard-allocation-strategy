package lerna.akka.cluster.sharding.coordinatoravoidance

import akka.actor.{ActorRef, ActorSystem, Address, ClassicActorSystemProvider}
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.sharding.ShardCoordinator.StartableAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.pattern.after
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.collection.MapView
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object CoordinatorAvoidanceShardAllocationStrategy {

  /** Settings of [[CoordinatorAvoidanceShardAllocationStrategy]]
    *
    * @param absoluteLimit
    *   How many shards this strategy rebalances at one time (absolute limit
    *   value)
    * @param relativeLimit
    *   How many shards this strategy rebalances at one time (relative limit
    *   value)
    * @param oldestNodesExcluded
    *   How many oldest nodes this strategy should avoid allocating shards
    * @param minCap
    *   How many at least nodes this strategy must allocate shards
    */
  final class Settings private (
      val absoluteLimit: Int,
      val relativeLimit: Double,
      val oldestNodesExcluded: Int,
      val minCap: Int
  ) {
    require(absoluteLimit >= 0, "absoluteLimit should be >= 0")
    require(
      relativeLimit >= 0.0 && relativeLimit <= 1.0,
      "relativeLimit should be >= 0.0 && <= 1.0"
    )
    require(oldestNodesExcluded >= 0, "oldestNodesExcluded should be >= 0")
    require(minCap >= 1, "minCap should be >= 1")
    private[CoordinatorAvoidanceShardAllocationStrategy] def limit(
        numberOfShards: Int
    ): Int = {
      import math.*
      // absoluteLimit == 0 means no limit.
      val actualAbsoluteLimit =
        if (absoluteLimit == 0) Int.MaxValue else absoluteLimit
      // Ensure at-least one shard should be rebalanced.
      max(1, min((relativeLimit * numberOfShards).toInt, actualAbsoluteLimit))
    }
    private[CoordinatorAvoidanceShardAllocationStrategy] def takeOldestEntries(
        entries: IndexedSeq[RegionEntry]
    ): IndexedSeq[RegionEntry] = {
      entries
        .sortBy(_.member)(Member.ageOrdering)
        .dropRight(minCap)
        .take(oldestNodesExcluded)
    }
    override def toString: String = {
      s"Settings(absoluteLimit = $absoluteLimit, relativeLimit = $relativeLimit, oldestNodesExcluded = $oldestNodesExcluded, minCap = $minCap)"
    }
  }

  object Settings {

    /** Creates settings from the given parameters
      *
      * @see
      *   [[Settings]]
      */
    def apply(
        absoluteLimit: Int = 0,
        relativeLimit: Double = 1.0,
        oldestNodesExcluded: Int = 0,
        minCap: Int = 1
    ): Settings = {
      new Settings(absoluteLimit, relativeLimit, oldestNodesExcluded, minCap)
    }

    /** Creates settings from the given configuration
      *
      * The given configuration should be the same layout as the default
      * configuration
      * `lerna.akka.cluster.sharding.coordinator-avoidance-shard-allocation-strategy`
      */
    def apply(config: Config, breakingBinaryCompat: Int): Settings = {
      val absoluteLimit = config.getInt("rebalance-absolute-limit")
      val relativeLimit = config.getDouble("rebalance-relative-limit")
      val oldestNodesExcluded =
        config.getInt("oldest-nodes-excluded-from-allocation")
      val minCap = config.getInt("allocation-nodes-min-cap")
      new Settings(absoluteLimit, relativeLimit, oldestNodesExcluded, minCap)
    }

    /** Creates settings from the default configuration
      * `lerna.akka.cluster.sharding.coordinator-avoidance-shard-allocation-strategy`
      *
      * This method resolves the default configuration from the given actor
      * system.
      */
    def apply(systemProvider: ClassicActorSystemProvider): Settings = {
      val config =
        systemProvider.classicSystem.settings.config.getConfig(
          "lerna.akka.cluster.sharding.coordinator-avoidance-shard-allocation-strategy"
        )
      Settings(config, breakingBinaryCompat = 0)
    }

  }

  private[coordinatoravoidance] trait ClusterStateProvider {
    def system: ActorSystem
    def clusterState: CurrentClusterState
    def selfMember: Member
    def start(): Unit
  }
  private[coordinatoravoidance] object ClusterStateProvider {
    def apply(system: ClassicActorSystemProvider): ClusterStateProvider = {
      new DefaultClusterStateProvider(system.classicSystem)
    }
  }
  private final class DefaultClusterStateProvider(val system: ActorSystem)
      extends ClusterStateProvider {
    private lazy val logger = LoggerFactory.getLogger(getClass)
    @volatile private var cluster: Cluster = _
    override def clusterState: CurrentClusterState = cluster.state
    override def selfMember: Member = cluster.selfMember
    override def start(): Unit = {
      cluster = Cluster(system)
      logger.debug("Initialized with {}", system)
    }
    override def toString: String = s"DefaultClusterProvider(system=${system})"
  }

  private val JoiningClusterStatuses: Set[MemberStatus] =
    Set(MemberStatus.Joining, MemberStatus.WeaklyUp)
  private val LeavingClusterStatuses: Set[MemberStatus] =
    Set(MemberStatus.Leaving, MemberStatus.Exiting, MemberStatus.Down)
  private val EmptyRebalanceResult = Future.successful(Set.empty[ShardId])

  private final case class RegionEntry(
      region: ActorRef,
      member: Member,
      shardIds: IndexedSeq[ShardId]
  )
  private object ShardSuitabilityOrdering extends Ordering[RegionEntry] {
    override def compare(x: RegionEntry, y: RegionEntry): Int = {
      val RegionEntry(_, memberX, allocatedShardsX) = x
      val RegionEntry(_, memberY, allocatedShardsY) = y
      if (memberX.status != memberY.status) {
        // prefer allocating to nodes that are not on their way out of the cluster
        val xIsLeaving = LeavingClusterStatuses(memberX.status)
        val yIsLeaving = LeavingClusterStatuses(memberY.status)
        xIsLeaving.compare(yIsLeaving)
      } else if (allocatedShardsX.size != allocatedShardsY.size) {
        // prefer the node with the least allocated shards
        allocatedShardsX.size.compare(allocatedShardsY.size)
      } else {
        // If each member has the same number of shards,
        // Prefer the node with the least member
        Member.ordering.compare(memberX, memberY)
      }
    }
  }

  /** Create a [[CoordinatorAvoidanceShardAllocationStrategy]]
    *
    * The strategy will use the given actor system for retrieving cluster
    * information. This method resolves settings of the strategy from the given
    * actor system.
    */
  def apply(
      systemProvider: ClassicActorSystemProvider
  ): CoordinatorAvoidanceShardAllocationStrategy = {
    val settings = Settings(systemProvider)
    CoordinatorAvoidanceShardAllocationStrategy(systemProvider, settings)
  }

  /** Create a [[CoordinatorAvoidanceShardAllocationStrategy]]
    *
    * The strategy will use the given actor system for retrieving cluster
    * information. This method creates the strategy with the given settings.
    */
  def apply(
      systemProvider: ClassicActorSystemProvider,
      settings: Settings
  ): CoordinatorAvoidanceShardAllocationStrategy = {
    val clusterStateProvider = ClusterStateProvider(systemProvider)
    new CoordinatorAvoidanceShardAllocationStrategy(
      clusterStateProvider,
      settings
    )
  }

}

/** Shard allocation strategy that avoids allocating shards to the oldest
  * node(s) hosting the [[akka.cluster.sharding.ShardCoordinator]]
  */
final class CoordinatorAvoidanceShardAllocationStrategy private[coordinatoravoidance] (
    clusterStateProvider: CoordinatorAvoidanceShardAllocationStrategy.ClusterStateProvider,
    settings: CoordinatorAvoidanceShardAllocationStrategy.Settings
) extends StartableAllocationStrategy {
  import CoordinatorAvoidanceShardAllocationStrategy.*

  private lazy val logger = LoggerFactory.getLogger(getClass)
  private def system: ActorSystem = clusterStateProvider.system
  private def clusterState: CurrentClusterState =
    clusterStateProvider.clusterState
  private def selfMember: Member = clusterStateProvider.selfMember

  override def start(): Unit = {
    clusterStateProvider.start()
    logger.debug(
      "Initialized with clusterStateProvider={}, settings={}",
      clusterStateProvider,
      settings
    )
  }

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]
  ): Future[ActorRef] = {
    logger.debug("Shard[{}] allocation from {}", shardId, requester)
    val regionEntries = regionEntriesFor(currentShardAllocations)
    val suitableRegionEntries = suitableRegionEntriesOf(regionEntries)
    val mostSuitableRegionEntryOption =
      suitableRegionEntries.minOption(ShardSuitabilityOrdering)
    logger.debug(
      "Shard[{}] allocation, most-suitable-option: {}, suitable: {}, all: {}",
      shardId,
      mostSuitableRegionEntryOption.map(entry =>
        s"RegionEntry(${entry.region}, ${entry.member})"
      ),
      suitableRegionEntries.map(entry =>
        s"RegionEntry(${entry.region}, ${entry.member})"
      ),
      regionEntries.map(entry =>
        s"RegionEntry(${entry.region}, ${entry.member})"
      )
    )
    mostSuitableRegionEntryOption match {
      case Some(mostSuitableRegionEntry) =>
        // Since `requester` is a shard region, there is at least one shard region.
        // Thus, in most case, we can choose the most suitable region.
        logger.debug(
          "Shard[{}] allocation to most-suitable: {}",
          shardId,
          mostSuitableRegionEntry.region
        )
        Future.successful(mostSuitableRegionEntry.region)
      case None =>
        // very unlikely to ever happen but possible because of cluster state view not yet updated when collecting
        // region entries, view should be updated after a very short time
        logger.debug(
          "Defer Shard[{}] allocation since the cluster state is not updated yet.",
          shardId
        )
        after(50.millis)(
          allocateShard(requester, shardId, currentShardAllocations)
        )(system)
    }
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]
  ): Future[Set[ShardId]] = {

    // Select shards to be rebalance based on given optimal number of shards and regions.
    def selectShardsToBeRebalance(
        optimalPerRegion: MapView[RegionEntry, Int],
        sortedAllEntries: Iterable[RegionEntry]
    ): Vector[ShardId] = {
      val selectedShards = Vector.newBuilder[ShardId]
      sortedAllEntries.foreach { regionEntry =>
        val optimalNumberOfShards = optimalPerRegion(regionEntry)
        val numberOfShardsToBeMoved =
          math.max(0, regionEntry.shardIds.size - optimalNumberOfShards)
        selectedShards ++= regionEntry.shardIds.take(numberOfShardsToBeMoved)
      }
      selectedShards.result()
    }

    // In the first phase the optimalPerRegion is rounded up.
    // Depending on number of shards per region and number of regions that might not be the exact optimal.
    def buildOptimalPerRegionInPhase1(
        numberOfShards: Int,
        allRegionEntries: Iterable[RegionEntry],
        suitableRegionEntries: Set[RegionEntry]
    ): Map[RegionEntry, Int] = {
      assert(suitableRegionEntries.nonEmpty)
      val numberOfSuitableRegions = suitableRegionEntries.size
      val optimalNumberOfShardsPerEachSuitableRegion = {
        // rounded up
        numberOfShards / numberOfSuitableRegions + (if (
                                                      numberOfShards % numberOfSuitableRegions == 0
                                                    ) 0
                                                    else 1)
      }
      val optimalPerRegion = Map.newBuilder[RegionEntry, Int]
      allRegionEntries.foreach { regionEntry =>
        val optimalNumberOfShards = {
          val isSuitableRegion = suitableRegionEntries.contains(regionEntry)
          if (isSuitableRegion) optimalNumberOfShardsPerEachSuitableRegion
          else 0
        }
        optimalPerRegion += regionEntry -> optimalNumberOfShards
      }
      optimalPerRegion.result().withDefault { entry =>
        logger.error(
          s"Not found optimal number of shards for $entry. Please report this error since this is a bug of $getClass."
        )
        0
      }
    }
    def rebalanceInPhase1(
        numberOfShards: Int,
        optimalPerRegion: MapView[RegionEntry, Int],
        sortedAllEntries: Iterable[RegionEntry]
    ): Set[ShardId] = {
      val selectedShards =
        selectShardsToBeRebalance(optimalPerRegion, sortedAllEntries)
      val rebalanceLimit = settings.limit(numberOfShards)
      selectedShards.take(rebalanceLimit).toSet
    }

    // In the second phase the optimalPerRegion is rounded down.
    // We look for diff of >= 2 below optimalPerRegion and rebalance that number of shards.
    def buildOptimalPerRegionInPhase2(
        optimalPerRegionInPhase1: Map[RegionEntry, Int]
    ): MapView[RegionEntry, Int] = {
      optimalPerRegionInPhase1.view.mapValues { optimalOfRegion =>
        math.max(0, optimalOfRegion - 1)
      }
    }
    def rebalanceInPhase2(
        numberOfShards: Int,
        optimalPerRegion: MapView[RegionEntry, Int],
        sortedAllEntries: Iterable[RegionEntry]
    ): Set[ShardId] = {
      val countBelowOptimal = {
        sortedAllEntries.map { regionEntry =>
          val optimalNumberOfShards = optimalPerRegion(regionEntry)
          math.max(0, optimalNumberOfShards - regionEntry.shardIds.size)
        }.sum
      }
      if (countBelowOptimal == 0) {
        Set.empty
      } else {
        val selectedShards =
          selectShardsToBeRebalance(optimalPerRegion, sortedAllEntries)
        val rebalanceLimit =
          math.min(countBelowOptimal, settings.limit(numberOfShards))
        selectedShards.take(rebalanceLimit).toSet
      }
    }

    if (rebalanceInProgress.nonEmpty) {
      // one rebalance at a time
      logger.debug(
        "No additional rebalance since the rebalance is in progress."
      )
      EmptyRebalanceResult
    } else {
      val sortedRegionEntries =
        regionEntriesFor(currentShardAllocations).toIndexedSeq
          .sorted(ShardSuitabilityOrdering)
      val suitableRegionEntries =
        suitableRegionEntriesOf(sortedRegionEntries)
      if (!isAGoodTimeToRebalance(suitableRegionEntries)) {
        logger.debug("Not good time to rebalance.")
        EmptyRebalanceResult
      } else {
        val numberOfShards = sortedRegionEntries.map(_.shardIds.size).sum
        val numberOfRegions = sortedRegionEntries.size
        val noShardsOrRegion = numberOfRegions == 0 || numberOfShards == 0
        if (noShardsOrRegion) {
          logger.debug(
            "No need to rebalance since there are no regions or shards. number-of-regions: {}, number-of-shards: {}",
            numberOfRegions,
            numberOfShards
          )
          EmptyRebalanceResult
        } else {
          val optimalPerRegionInPhase1 =
            buildOptimalPerRegionInPhase1(
              numberOfShards,
              sortedRegionEntries,
              suitableRegionEntries
            )
          val shardsToBeRebalancedInPhase1 =
            rebalanceInPhase1(
              numberOfShards,
              optimalPerRegionInPhase1.view,
              sortedRegionEntries
            )
          logger.debug(
            "Rebalance[Phase1]: optimal-per-region: {}, shards-to-be-rebalanced: {}",
            optimalPerRegionInPhase1.mkString("[", ",", "]"),
            shardsToBeRebalancedInPhase1.mkString("[", ",", "]")
          )
          if (shardsToBeRebalancedInPhase1.nonEmpty) {
            Future.successful(shardsToBeRebalancedInPhase1)
          } else {
            val optimalPerRegionInPhase2 = buildOptimalPerRegionInPhase2(
              optimalPerRegionInPhase1
            )
            val shardsToBeRebalancedInPhase2 =
              rebalanceInPhase2(
                numberOfShards,
                optimalPerRegionInPhase2,
                sortedRegionEntries
              )
            logger.debug(
              "Rebalance[Phase2]: optimal-per-region: {}, shards-to-be-rebalanced: {}",
              optimalPerRegionInPhase2.mkString("[", ",", "]"),
              shardsToBeRebalancedInPhase2.mkString("[", ",", "]")
            )
            if (shardsToBeRebalancedInPhase2.isEmpty) EmptyRebalanceResult
            else Future.successful(shardsToBeRebalancedInPhase2)
          }
        }
      }
    }
  }

  private def regionEntriesFor(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]
  ): Iterable[RegionEntry] = {
    val addressToMember: Map[Address, Member] =
      clusterState.members.map(m => m.address -> m).toMap
    currentShardAllocations.flatMap { case (region, shardIds) =>
      val regionAddress = {
        if (region.path.address.hasLocalScope) selfMember.address
        else region.path.address
      }
      val memberForRegion = addressToMember.get(regionAddress)
      // If the member is unknown (very unlikely but not impossible) because of view not updated yet,
      // that node is ignored for this invocation.
      memberForRegion.map(member => RegionEntry(region, member, shardIds))
    }
  }

  // Return non-empty entries if there is at least one up-region.
  private def suitableRegionEntriesOf(
      regionEntries: Iterable[RegionEntry]
  ): Set[RegionEntry] = {
    val allEntries: Vector[RegionEntry] = regionEntries.toVector
    val olderEntries: IndexedSeq[RegionEntry] = {
      // Excludes k-oldest members as possible.
      // The oldest member (or older members) may host ShardCoordinator.
      settings.takeOldestEntries(allEntries)
    }
    allEntries.toSet.removedAll(olderEntries)
  }

  private def isAGoodTimeToRebalance(
      suitableRegionEntries: Iterable[RegionEntry]
  ): Boolean = {
    if (suitableRegionEntries.isEmpty) {
      // If we have no suitable regions, probably not a good time to rebalance.
      false
    } else {
      // Rebalance requires ack from regions and proxies - no need to rebalance if it cannot be completed
      // we currently only look at same dc but proxies in other dcs may delay complete as well right now.
      // FIXME see related issue https://github.com/akka/akka/issues/29589
      val neededMembersReachable =
        !clusterState.members.exists(m =>
          m.dataCenter == selfMember.dataCenter && clusterState.unreachable(m)
        )
      // No members in same dc joining, we want that to complete before rebalance, such nodes should reach Up soon.
      val membersInProgressOfJoining =
        clusterState.members.exists(m =>
          m.dataCenter == selfMember.dataCenter && JoiningClusterStatuses(
            m.status
          )
        )
      // No members in same dc leaving, we want that to complete before rebalance.
      // We have a complex situation when the shard coordinator shuts down.
      // Avoiding a rebalance when a node is leaving is an easy way to handle such a situation.
      val membersInProgressOfLeaving =
        clusterState.members.exists(m =>
          m.dataCenter == selfMember.dataCenter && LeavingClusterStatuses(
            m.status
          )
        )
      neededMembersReachable && !membersInProgressOfJoining && !membersInProgressOfLeaving
    }
  }

  override def toString: String =
    s"CoordinatorAvoidanceShardAllocationStrategy($settings)"

}
