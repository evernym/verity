package com.evernym.verity.actor.base

import akka.actor.{Actor, ActorPath}
import com.evernym.verity.actor.ShardUtil.SHARD_ACTOR_PATH_ELEMENT
import com.evernym.verity.actor.UserActorUtil.USER_ACTOR_PATH_ELEMENT
import com.evernym.verity.actor.base.EntityIdentifier.{EntityIdentity, parsePath}
import com.evernym.verity.actor.persistence.SupervisorUtil
import com.evernym.verity.constants.ActorNameConstants.{CLUSTER_SINGLETON_MANAGER, DEFAULT_ENTITY_TYPE}


trait EntityIdentifier { this: Actor =>

  lazy val entityIdentity: EntityIdentity = parsePath(self.path)

  lazy val entityId: String = entityIdentity.entityId

  lazy val entityType: String = entityIdentity.entityType

  //a unique entity identifier including entity type as well
  lazy val actorId: String =
    if (entityType != entityId && entityType != "/") entityType + "-" + entityId
    else entityId
}

//trait EntityIdentifier { this: Actor =>
//
//  lazy val effectiveActorPathString: String = {
//    val pathString = self.path.toString
//    if (isShardedActor)
//      pathString.split(s"/${SHARD_ACTOR_PATH_ELEMENT}/").last
//    else if (isUserActor)
//      pathString.split(s"/${USER_ACTOR_PATH_ELEMENT}/").last
//    else throw new RuntimeException("unsupported actor path")
//  }
//  lazy val isShardedActor: Boolean = self.path.toString.contains(SHARD_ACTOR_PATH_ELEMENT)
//  lazy val isUserActor: Boolean = self.path.toString.contains(USER_ACTOR_PATH_ELEMENT)
//  lazy val isSupervised: Boolean = self.path.toString.contains(SupervisorUtil.SUPERVISED_ACTOR_NAME)
//  lazy val isClusterSingletonChild: Boolean = self.path.toString.contains(CLUSTER_SINGLETON_MANAGER)
//
//  //entity id
//  lazy val entityId: String = {
//    if (isSupervised) extractNameFromPath(effectiveActorPathString, 1)
//    else extractNameFromPath(effectiveActorPathString)
//  }
//
//  //entity type/name
//  //NOTE: DON'T change this without making sure it would be backward compatible
//  // for already persisted actors (as this entityType is used to construct persistenceId)
//  lazy val entityType: String =
//  if (isSupervised) {
//    if (isShardedActor || isClusterSingletonChild) extractNameFromPath(effectiveActorPathString, 3)
//    else DEFAULT_ENTITY_TYPE
//  } else {
//    if (isShardedActor || isClusterSingletonChild) extractNameFromPath(effectiveActorPathString, 2)
//    else DEFAULT_ENTITY_TYPE
//  }
//
//  //a unique entity identifier including entity name as well
//  lazy val actorId: String =
//    if (entityType != entityId && entityType != "/") entityType + "-" + entityId
//    else entityId
//
//  def extractNameFromPath(pathString: String, elementNrFromEnd: Int = 0): String = {
//    if (! pathString.contains("/")) pathString
//    else if (elementNrFromEnd == 0) pathString.substring(pathString.lastIndexOf("/")+1)
//    else extractNameFromPath(pathString.substring(0, pathString.lastIndexOf("/")), elementNrFromEnd-1)
//  }
//}

object EntityIdentifier {
  // **NOTE**:
  // DON'T change this without making sure it would be backward compatible with
  // already existing persisted actors
  //
  // Both entityId and entityType are use for persistentId (see: actorId)

  class InvalidPath(msg: String) extends Exception(msg)

  val sharedPattern = Seq("system", SHARD_ACTOR_PATH_ELEMENT)
  val clusterSingletonChildPattern = Seq(USER_ACTOR_PATH_ELEMENT, CLUSTER_SINGLETON_MANAGER, "singleton")
  val userPattern = Seq(USER_ACTOR_PATH_ELEMENT)

  case class EntityIdentity(entityId: String,
                           entityType: String,
                           shard: Option[Int],
                           isUserActor: Boolean,
                           isSupervised: Boolean,
                           isClusterSingletonChild: Boolean) {
    def isShardedActor: Boolean = shard.isDefined
  }

  def parsePath(path: ActorPath): EntityIdentity = {
    val elements = path.elements.toSeq

    supervisedNormalized(elements) match {
      case (isSupervised, e) if isShardedActor(e)          => extractShardedIdentity(e, isSupervised)
      case (isSupervised, e) if isClusterSingletonChild(e) => extractClusterSingletonChildIdentity(e, isSupervised)
      case (isSupervised, e) if isUserActor(e)             => extractUserIdentity(e, isSupervised)
    }
  }



  private def supervisedNormalized(elements: Seq[String]): (Boolean, Seq[String]) = {
    if (elements.contains(SupervisorUtil.SUPERVISED_ACTOR_NAME)) {
      val count = elements.count(_ == SupervisorUtil.SUPERVISED_ACTOR_NAME)
      if (count != 1) {
        throw new InvalidPath(
          s"Invalid actor path - more than one ($count) 'supervised' elements in the path - ${elements.mkString("/")}"
        )
      }

      (true, elements.filterNot(_ == SupervisorUtil.SUPERVISED_ACTOR_NAME))
    }
    else {
      (false, elements)
    }
  }

  private def isShardedActor(elements: Seq[String]): Boolean = elements.startsWith(sharedPattern)

  private def extractShardedIdentity(elements: Seq[String], isSupervised: Boolean): EntityIdentity = {
    elements match {
      case Seq("system", SHARD_ACTOR_PATH_ELEMENT, entityType, shard, entityId) =>
        EntityIdentity(
          entityId,
          entityType,
          Option(shard.toInt),
          isUserActor = false,
          isSupervised = isSupervised,
          isClusterSingletonChild = false
        )
      case Seq("system", SHARD_ACTOR_PATH_ELEMENT, entityType, shard, _ /*parentEntityId*/, entityId) =>
        EntityIdentity(
          entityId,
          entityType+"Child",
          Option(shard.toInt),
          isUserActor = false,
          isSupervised = isSupervised,
          isClusterSingletonChild = false
        )
      case _ =>
        throw new InvalidPath(
          s"Invalid actor path - has an unexpected pattern for sharded actor - ${elements.mkString("/")}"
        )
    }
  }

  private def isClusterSingletonChild(elements: Seq[String]): Boolean = elements.startsWith(clusterSingletonChildPattern)

  private def extractClusterSingletonChildIdentity(elements: Seq[String], isSupervised: Boolean): EntityIdentity = {
    elements match {
      case Seq(USER_ACTOR_PATH_ELEMENT, CLUSTER_SINGLETON_MANAGER, "singleton", parentEntityId, entityId) =>
        EntityIdentity(
          entityId,
          parentEntityId+"Child",
          None,
          isUserActor = false,
          isSupervised = isSupervised,
          isClusterSingletonChild = true
        )
      case Seq(USER_ACTOR_PATH_ELEMENT, CLUSTER_SINGLETON_MANAGER, "singleton", entityId) =>
        EntityIdentity(
          entityId,
          CLUSTER_SINGLETON_MANAGER,
          None,
          isUserActor = false,
          isSupervised = isSupervised,
          isClusterSingletonChild = true
        )
      case _ =>
        throw new InvalidPath(
          s"Invalid actor path - has an unexpected pattern for cluster singleton` actor - ${elements.mkString("/")}"
        )
    }
  }

  private def isUserActor(elements: Seq[String]): Boolean = elements.startsWith(userPattern)

  private def extractUserIdentity(elements: Seq[String], isSupervised: Boolean): EntityIdentity = {
    elements match {
      case Seq(USER_ACTOR_PATH_ELEMENT, entityId) =>
        EntityIdentity(
          entityId,
          DEFAULT_ENTITY_TYPE,
          None,
          isUserActor = true,
          isSupervised = isSupervised,
          isClusterSingletonChild = false
        )
      case _ =>
        throw new InvalidPath(
          s"Invalid actor path - has an unexpected pattern for user actor - ${elements.mkString("/")}"
        )
    }
  }
}