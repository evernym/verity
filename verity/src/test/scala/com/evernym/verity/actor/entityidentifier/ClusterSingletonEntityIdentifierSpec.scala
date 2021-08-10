package com.evernym.verity.actor.entityidentifier

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.{ActorDetail, GetActorDetail}
import com.evernym.verity.actor.cluster_singleton.watcher.ForEntityItemWatcher
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, ForWatcherManager}
import com.evernym.verity.actor.entityidentifier.base.EntityIdentifierBaseSpec
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.constants.ActorNameConstants._

class ClusterSingletonEntityIdentifierSpec
  extends EntityIdentifierBaseSpec
    with ProvidesMockPlatform {

  val clusterSingletonProxy = platform.singletonParentProxy

  "A cluster singleton parent actor" - {

    "KeyValueMapper actor" - {
      "when asked for GetPersistentActorDetail" - {
        "should respond with correct entity identifiers" in {
          clusterSingletonProxy ! ForKeyValueMapper(GetPersistentActorDetail)
          val actualDetail = expectMsgType[PersistentActorDetail]
          val expectedDetail = PersistentActorDetail(
            ActorDetail(CLUSTER_SINGLETON_MANAGER, KEY_VALUE_MAPPER_ACTOR_NAME, s"$CLUSTER_SINGLETON_MANAGER-$KEY_VALUE_MAPPER_ACTOR_NAME"),
            s"$CLUSTER_SINGLETON_MANAGER-$KEY_VALUE_MAPPER_ACTOR_NAME", 0, 0
          )
          assertPersistentActorDetail(actualDetail, expectedDetail)
        }
      }
    }

    "WatcherManager actor" - {
      "when asked for GetActorDetail" - {
        "should respond with correct entity identifiers" in {
          clusterSingletonProxy ! ForBaseWatcherManager(GetActorDetail)
          val actualDetail = expectMsgType[ActorDetail]
          val expectedDetail = ActorDetail(CLUSTER_SINGLETON_MANAGER, WATCHER_MANAGER, s"$CLUSTER_SINGLETON_MANAGER-$WATCHER_MANAGER")
          assertActorDetail(actualDetail, expectedDetail)
        }
      }
    }

    "UserAgentPairwiseWatcherManager actor" - {
      "when asked for GetActorDetail" - {
        "should respond with correct entity identifiers" in {
          clusterSingletonProxy ! ForEntityItemWatcher(GetActorDetail)
          val actualDetail = expectMsgType[ActorDetail]
          val expectedDetail = ActorDetail("watcher-managerChild", "ActorWatcher", s"watcher-managerChild-ActorWatcher")
          assertActorDetail(actualDetail, expectedDetail)
        }
      }
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

case class ForBaseWatcherManager(override val cmd: Any) extends ForWatcherManager {
  def getActorName: String = WATCHER_MANAGER
}