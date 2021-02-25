package com.evernym.verity.actor.itemmanager

import java.time.ZonedDateTime

import akka.actor.{ActorLogging, Props}
import akka.event.Logging._
import com.evernym.verity.actor._
import com.evernym.verity.actor.appStateManager.ErrorEvent
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemManagerEntityId, ItemType, VersionId}
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.VerKey


trait ItemCommandHandlerBase extends ActorLogging { this: BasePersistentActor =>

  def receiveCmdHandler: Receive

  def ownerVerKey: Option[VerKey]

  def verifySignature(signature: String): Boolean = {
    throw new NotImplementedError("signature verification not yet implemented")
  }

  def validateMsg(msg: Any): Boolean = {
    //can be overridden by implementing class if needed
    true
  }

  def preMsgHandler(msg: Any): Unit = {
    //can be overridden by implementing class if needed
  }

  def verifySignatureIfRequired(cmd: Any): Unit = {
    cmd match {
      case ecw: ExternalCmdWrapper =>
        ownerVerKey.foreach { _ =>
          verifySignature(ecw.ownerSignature.orNull)
        }
      case _ =>
    }
  }

  final override def receiveCmd: Receive = {
    case cmd: ItemCmdWrapperBase if receiveCmdHandler.isDefinedAt(cmd.msg) => processMsg(cmd.msg)
    case m if receiveCmdHandler.isDefinedAt(m) => processMsg(m)
  }

  def processMsg(msg: Any): Unit = {
    logMsg("received: " + msg, DebugLevel)
    if (validateMsg(msg)) {
      logMsg("validation successful: " + msg, DebugLevel)
      preMsgHandler(msg)
      logMsg("pre cmd handler executed: " + msg, DebugLevel)
      verifySignatureIfRequired(msg)
      receiveCmdHandler(msg)
      logMsg("receive cmd handler executed: " + msg, DebugLevel)
    } else {
      logMsg("validation failed: " + msg, DebugLevel)
    }
  }

  def logMsg(msg: String, level: LogLevel): Unit = {
    val prefixedMsg = s"ItemActorType:$persistenceId: $msg"
    level match {
      case DebugLevel   => logger.debug(prefixedMsg)
      case InfoLevel    => logger.info(prefixedMsg)
      case WarningLevel => logger.warn(prefixedMsg)
      case ErrorLevel   => logger.error(prefixedMsg)
      case x            => throw new RuntimeException("unsupported log level: " + x)
    }
  }

  def handleUnsupportedCondition(message: String): Unit = {
    val prefixedMsg = s"unsupported condition occurred: $message"
    logMsg(prefixedMsg, ErrorLevel)
    throw new RuntimeException(message)
  }

  def notifyAppStateManager(eventParam: ErrorEvent): Unit = {
    publishAppStateEvent(eventParam)
  }

  def unhandledMsg(receivedMsg: Any, responseToBeSent: Any): Unit = {
    logMsg(s"received '$receivedMsg' for non-configured/stale $entityType, won't be handled", DebugLevel)
    //this is to make sure it doesn't create loop if the incoming message was sent by self (due to scheduled job etc)
    if (sender != self) {
      sender ! responseToBeSent
    }
  }
}

object ItemManager extends HasProps {
  def props(implicit conf: AppConfig): Props = Props(new ItemManager)
}

class ItemManager(implicit val appConfig: AppConfig) extends ItemManagerBase


object ItemContainer extends HasProps {
  def props(implicit conf: AppConfig): Props = Props(new ItemContainer)
}

class ItemContainer(implicit val appConfig: AppConfig) extends ItemContainerBase

trait ItemCmdWrapperBase extends ActorMessage {
  def msg: Any
}

/**
 * command wrapper which is used by message sender (non internal commands)
 * @param msg
 * @param ownerSignature
 */
case class ExternalCmdWrapper(msg: Any, ownerSignature: Option[String]) extends ItemCmdWrapperBase

/**
 * command wrapper used by either item manager or item container actors to communicate internally
 * @param msg
 */
case class InternalCmdWrapper(msg: Any) extends ItemCmdWrapperBase

case object GetState extends ActorMessage
case class GetItem(id: ItemId) extends ActorMessage
case class UpdateItem(id: ItemId, status: Option[Int]=None, detailOpt: Option[String]=None, createdAt: Option[ZonedDateTime]=None) extends ActorMessage
case class SaveItemFromMigration(uip: UpdateItem) extends ActorMessage

case class ItemCmdResponse(msg: Any, senderEntityId: ItemContainerEntityId) extends ActorMessage

case object ItemManagerConfigNotYetSet extends ActorMessage

case object ItemManagerConfigAlreadySet extends ActorMessage

case class SetItemManagerConfig(itemType: ItemType,
                                ownerVerKey: Option[VerKey],
                                migrateItemsToNextLinkedContainer: Boolean,
                                migrateItemsToLatestVersionedContainers: Boolean) extends ActorMessage {
  require (migrateItemsToNextLinkedContainer != migrateItemsToLatestVersionedContainers,
    "one and only one of 'migrateItemsToNextLinkedContainer' and 'migrateItemsToLatestVersionedContainers' should be set to true")
}

case object ItemContainerStaleOrConfigNotYetSet extends ActorMessage

case object ItemContainerDeleted extends ActorMessage

case object ItemContainerConfigAlreadySet extends ActorMessage

case class SetItemContainerConfig(itemType: ItemType,
                                  versionId: VersionId,
                                  managerEntityId: ItemManagerEntityId,
                                  ownerVerKey: Option[VerKey],
                                  prevContainerEntityId: Option[ItemContainerEntityId],
                                  migrateItemsToNextLinkedContainer: Boolean,
                                  migrateItemsToLatestVersionedContainers: Boolean) extends ActorMessage {
  require (migrateItemsToNextLinkedContainer != migrateItemsToLatestVersionedContainers,
    "one and only one of 'migrateItemsToNextLinkedContainer' and 'migrateItemsToLatestVersionedContainers' should be set to true")
}

case class MigrateItems(toContainerEntityIdOpt: Option[ItemContainerEntityId]=None)


//state
/**
 *
 * @param prevId previous container's entity id
 * @param nextId next container's entity id
 */
case class ItemContainerLink(prevId: Option[ItemContainerEntityId], nextId: Option[ItemContainerEntityId])


object ItemCommonType {
  type ItemId = String
  type ItemType = String
  type VersionId = Int
  type ItemManagerEntityId = String
  type ItemContainerEntityId = String
}


object ItemCommonConstants {

  final val ENTITY_ID_MAPPER_VERSION_V1 = 1

  final val NO_CONTAINER_ID = "-1"

  final val ITEM_MIGRATION_STRATEGY_AUTO = 0
  final val ITEM_MIGRATION_STRATEGY_MANUAL = 1

  final val ITEM_STATUS_ACTIVE = 0
  final val ITEM_STATUS_REMOVED = 1
  final val ITEM_STATUS_MIGRATED = 2

  def getStatusString(status: Int): String = {
    status match {
      case ITEM_STATUS_ACTIVE => "active"
      case ITEM_STATUS_REMOVED => "removed"
      case ITEM_STATUS_MIGRATED => "migrated"
    }
  }
}
