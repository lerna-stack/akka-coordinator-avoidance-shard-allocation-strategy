package akka.internal

import akka.actor.{ActorPath, ActorRefProvider, MinimalActorRef}

/** Bridges to the internal APIs of [[akka.actor.MinimalActorRef]]
  *
  * If Akka changes the APIs, our tests will be failed. However, the changes
  * don't break our application code. Thus, we accept the hacks to use the
  * internal APIs.
  */
final class DummyActorRef(val path: ActorPath) extends MinimalActorRef {
  override def provider: ActorRefProvider = ???
}
