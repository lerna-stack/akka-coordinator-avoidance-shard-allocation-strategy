package akka.internal

import akka.cluster.{ClusterSettings, UniqueAddress}
import akka.util.Version

/** Bridges to the internal APIs of [[akka.cluster.Member]]
  *
  * If Akka changes the APIs, our tests will be failed. However, the changes
  * don't break our application code. Thus, we accept the hacks to use the
  * internal APIs.
  */
object Member {

  def apply(
      uniqueAddress: UniqueAddress,
      roles: Set[String],
      appVersion: Version
  ): akka.cluster.Member = {
    akka.cluster.Member(uniqueAddress, roles, appVersion)
  }

  def apply(
      uniqueAddress: UniqueAddress,
      dataCenter: String,
      appVersion: Version
  ): akka.cluster.Member = {
    val dataCenterRole = ClusterSettings.DcRolePrefix + dataCenter
    apply(uniqueAddress, Set(dataCenterRole), appVersion)
  }

  def apply(
      uniqueAddress: UniqueAddress,
      appVersion: Version
  ): akka.cluster.Member = {
    apply(uniqueAddress, ClusterSettings.DefaultDataCenter, appVersion)
  }

}
