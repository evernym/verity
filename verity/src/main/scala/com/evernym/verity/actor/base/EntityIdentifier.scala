package com.evernym.verity.actor.base

import akka.actor.{Actor, ActorPath}
import com.evernym.verity.actor.ShardUtil
import com.evernym.verity.constants.ActorNameConstants


trait EntityIdentifier { this: Actor =>

  lazy val isShardedActor: Boolean = self.path.toString.contains(ShardUtil.SHARD_ACTOR_PATH_ELEMENT)
  lazy val isClusterSingletonChild: Boolean = self.path.toString.contains(ActorNameConstants.CLUSTER_SINGLETON_MANAGER)

  //entity id
  lazy val entityId: String = extractNameFromPath(self.path)

  //entity type/name
  lazy val entityName: String =
    if (isShardedActor || isClusterSingletonChild) extractNameFromPath(self.path, nestLevel = 1)
    else extractNameFromPath(self.path)

  //a unique entity identifier including entity name as well
  lazy val actorId: String = if (entityName != entityId) entityName + "-" + entityId else entityId

  def extractNameFromPath(path: ActorPath, nestLevel: Int = 0): String = {
    if (nestLevel== 0) path.name
    else extractNameFromPath(path.parent, nestLevel-1)
  }
}
