package com.evernym.verity.actor.itemmanager

import java.time.ZonedDateTime
import akka.actor.{ActorLogging, Props}
import akka.event.Logging._
import com.evernym.verity.actor._
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemManagerEntityId}
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.ITEM_CONTAINER_MAPPER_CLASS

import scala.concurrent.ExecutionContext


trait ItemCommandHandlerBase extends ActorLogging { this: BasePersistentActor =>

  def receiveCmdHandler: Receive

  def validateMsg(msg: Any): Boolean = {
    //can be overridden by implementing class if needed
    true
  }

  def preMsgHandler(msg: Any): Unit = {
    //can be overridden by implementing class if needed
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

  def unhandledMsg(receivedMsg: Any, responseToBeSent: Any): Unit = {
    logMsg(s"received '$receivedMsg' for non-configured/stale $entityType, won't be handled", DebugLevel)
    //this is to make sure it doesn't create loop if the incoming message was sent by self (due to scheduled job etc)
    if (sender != self) {
      sender ! responseToBeSent
    }
  }

  protected def buildItemContainerEntityId(itemManagerEntityId: ItemManagerEntityId,
                                           itemId: ItemId): ItemContainerEntityId = {
    itemManagerEntityId + "-" + itemContainerMapper.getItemContainerId(itemId)
  }

  lazy val itemContainerMapper: ItemContainerMapper = {
    val clazz = appConfig.getStringReq(ITEM_CONTAINER_MAPPER_CLASS)
    Class
      .forName(clazz)
      .getConstructor()
      .newInstance()
      .asInstanceOf[ItemContainerMapper]
  }
}

object ItemManager {
  def props(executionContext: ExecutionContext)(implicit conf: AppConfig): Props = Props(new ItemManager(executionContext))
  val defaultPassivationTimeout = 600
}

class ItemManager(executionContext: ExecutionContext)(implicit val appConfig: AppConfig) extends ItemManagerBase {
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}


object ItemContainer {
  def props(executionContext: ExecutionContext)(implicit conf: AppConfig): Props = Props(new ItemContainer(executionContext))
  val defaultPassivationTimeout = 600
}

class ItemContainer(executionContext: ExecutionContext)(implicit val appConfig: AppConfig) extends ItemContainerBase {
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

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

case class SetItemManagerConfig(itemManagerId: ItemId,
                                migrateItemsToNextLinkedContainer: Boolean) extends ActorMessage

case object ItemContainerStaleOrConfigNotYetSet extends ActorMessage

case object ItemContainerDeleted extends ActorMessage

case object ItemContainerConfigAlreadySet extends ActorMessage

case class SetItemContainerConfig(managerEntityId: ItemManagerEntityId,
                                  prevContainerEntityId: Option[ItemContainerEntityId],
                                  migrateItemsToNextLinkedContainer: Boolean) extends ActorMessage

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
  type ItemManagerEntityId = String
  type ItemContainerEntityId = String
}


object ItemCommonConstants {

  final val ENTITY_ID_MAPPER_VERSION_V1 = 1

  final val NO_CONTAINER_ID = "-1"

  final val ITEM_MIGRATION_STRATEGY_AUTO = 0
  final val ITEM_MIGRATION_STRATEGY_MANUAL = 1

  final val ITEM_STATUS_REMOVED = 0
  final val ITEM_STATUS_ACTIVE = 1
  final val ITEM_STATUS_MIGRATED = 2

  def getStatusString(status: Int): String = {
    status match {
      case ITEM_STATUS_REMOVED  => "removed"
      case ITEM_STATUS_ACTIVE   => "active"
      case ITEM_STATUS_MIGRATED => "migrated"
      case other                => throw new RuntimeException("unsupported item status: " + other)
    }
  }
}
