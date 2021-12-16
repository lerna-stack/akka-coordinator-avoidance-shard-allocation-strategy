# Coordinator Avoidance Shard Allocation Strategy

## Motivation
We faced response time (latency) spikes during a rolling update in some specific situations with *Akka Cluster Sharding*.
This challenge is because we lose the `ShardCoordinator` and `ShardHome` cache simultaneously.
To address this challenge, we've implemented a custom shard allocation strategy (called *Coordinator Avoidance Shard Allocation Strategy*).
This strategy avoids allocating `Shard`s to the oldest node hosting the `ShardCoordinator`.
This strategy can reduce such latency spikes if we ensure that all nodes cached all ShardHomes before shutting the oldest node.
This strategy reduces about 80% latency at stopping the oldest node.
The evaluation and the first design are available [here](docs/design.md).


## Getting Started
You have to add a dependency into your project to use this library.
Add the following lines to your `build.sbt` file:

**Stable Release (Not Available)**  
Replace `"X.Y.Z"` with the actual version you want to use.
```scala
libraryDependencies += "com.lerna-stack" %% "akka-coordinator-avoidance-shard-allocation-strategy" % "X.Y.Z"
```

**Unstable Release (SNAPSHOT, Not Available)**  
Replace `"X.Y.Z"` with the actual version you want to use.
```scala
// You have to refer to Sonatype if you use a snapshot version.
resolvers += Resolver.sonatypeRepo("snapshots") 

libraryDependencies += "com.lerna-stack" %% "akka-coordinator-avoidance-shard-allocation-strategy" % "X.Y.Z-SNAPSHOT"
```

### Using with Akka Cluster Sharding (Classic)

Since this library depends on *Akka Cluster Sharding* as a provided scope,
you have to add Akka Cluster Sharding (Classic) into your project like below explicitly.
```scala
libraryDependencies += "com.typesafe.akka" %% "akka-cluster-sharding" % "2.6.17"
```
Note that you can choose another version of Akka Cluster Sharding.
For more details, please see [Supported Akka versions](#supported-akka-versions).

You can specify this strategy using [`ClusterSharding.start`](https://doc.akka.io/api/akka/2.6/akka/cluster/sharding/ClusterSharding.html#start(typeName:String,entityProps:akka.actor.Props,settings:akka.cluster.sharding.ClusterShardingSettings,messageExtractor:akka.cluster.sharding.ShardRegion.MessageExtractor,allocationStrategy:akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy,handOffStopMessage:Any):akka.actor.ActorRef) method.
An example is available [here](src/test/scala/lerna/akka/cluster/sharding/coordinatoravoidance/CoordinatorAvoidanceShardAllocationStrategyWithClassicCompileOnlySpec.scala).

We select default setting values to be effective and safe in most cases.
You can override the settings defined at [reference.conf](src/main/resources/reference.conf).

### Using with Akka Cluster Sharding (Typed)

Since this library depends on *Akka Cluster Sharding* as a provided scope,
you have to add Akka Cluster Sharding (Typed) into your project like below explicitly.
```scala
libraryDependencies += "com.typesafe.akka" %% "akka-cluster-sharding-typed" % "2.6.17"
```
Note that you can choose another version of Akka Cluster Sharding.
For more details, please see [Supported Akka versions](#supported-akka-versions).

You can specify this strategy using [`Entity.withAllocationStrategy`](https://doc.akka.io/api/akka/2.6/akka/cluster/sharding/typed/scaladsl/Entity.html#withAllocationStrategy(newAllocationStrategy:akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy):akka.cluster.sharding.typed.scaladsl.Entity[M,E]) method.
An example is available [here](src/test/scala/lerna/akka/cluster/sharding/coordinatoravoidance/CoordinatorAvoidanceShardAllocationStrategyWithTypedCompileOnlySpec.scala)

We select default setting values to be effective and safe in most cases.
You can override the settings defined at [reference.conf](src/main/resources/reference.conf).


## Supported versions

### Supported Akka versions
We recommend using *Akka 2.6.16 or above* since we've tested this library with *Akka 2.6.16*.
However, this library can work with *Akka 2.6.0 or above* in theory.

### Supported Scala versions
This library supports Scala 2.13 only now.

### Supported JDK versions
This library supports JDK8 and JDK11.


## Changelog
You can see all the notable changes in [CHANGELOG](CHANGELOG.md).


## For Contributors

[CONTRIBUTING](CONTRIBUTING.md) might help us.

## License

akka-coordinator-avoidance-shard-allocation-strategy is released under the terms of the [Apache License Version 2.0](./LICENSE).

<!-- Escape to set blank lines and use "*" -->
\
\
\* The names of the companies and products described in this site are trademarks or registered trademarks of the respective companies.  
\* Akka is a trademark of Lightbend, Inc.

© 2021 TIS Inc.
