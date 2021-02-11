package com.evernym.verity.actor.base

import akka.actor.Actor
import com.evernym.verity.constants.ActorNameConstants.DEFAULT_ENTITY_TYPE
import com.evernym.verity.actor.{ShardUtil, UserActorUtil}
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.constants.ActorNameConstants


trait EntityIdentifier { this: Actor =>

  lazy val effectiveActorPathString: String = {
    val pathString = self.path.toString
      if (isShardedActor)
        pathString.split(s"/${ShardUtil.SHARD_ACTOR_PATH_ELEMENT}/").last
      else if (isUserActor)
        pathString.split(s"/${UserActorUtil.USER_ACTOR_PATH_ELEMENT}/").last
      else throw new RuntimeException("unsupported actor path")
  }
  lazy val isShardedActor: Boolean = self.path.toString.contains(ShardUtil.SHARD_ACTOR_PATH_ELEMENT)
  lazy val isUserActor: Boolean = self.path.toString.contains(UserActorUtil.USER_ACTOR_PATH_ELEMENT)
  lazy val isSupervised: Boolean = self.path.toString.contains(SupervisorUtil.SUPERVISED_ACTOR_NAME)
  lazy val isClusterSingletonChild: Boolean = self.path.toString.contains(ActorNameConstants.CLUSTER_SINGLETON_MANAGER)

  //entity id
  lazy val entityId: String = {
    if (isSupervised) extractNameFromPath(effectiveActorPathString, 1)
    else extractNameFromPath(effectiveActorPathString)
  }

  //entity type/name
  //NOTE: DON'T change this without making sure it would be backward compatible
  // for already persisted actors (as this entityType is used to construct persistenceId)
  lazy val entityType: String =
    if (isSupervised) {
      if (isShardedActor || isClusterSingletonChild) extractNameFromPath(effectiveActorPathString, 3)
      else DEFAULT_ENTITY_TYPE
    } else {
      if (isShardedActor || isClusterSingletonChild) extractNameFromPath(effectiveActorPathString, 2)
      else DEFAULT_ENTITY_TYPE
    }

  //a unique entity identifier including entity name as well
  lazy val actorId: String =
    if (entityType != entityId && entityType != "/") entityType + "-" + entityId
    else entityId

  def extractNameFromPath(pathString: String, elementNrFromEnd: Int = 0): String = {
    if (! pathString.contains("/")) pathString
    else if (elementNrFromEnd == 0) pathString.substring(pathString.lastIndexOf("/")+1)
    else extractNameFromPath(pathString.substring(0, pathString.lastIndexOf("/")), elementNrFromEnd-1)
  }
}