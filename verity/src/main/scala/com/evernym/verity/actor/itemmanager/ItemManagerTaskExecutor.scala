package com.evernym.verity.actor.itemmanager

import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.base.{CoreActorExtended, DoNotRecordLifeCycleMetrics}
import com.evernym.verity.constants.ActorNameConstants.ITEM_CONTAINER_REGION_ACTOR_NAME
import com.evernym.verity.actor.{ActorMessage, ForIdentifier}
import com.evernym.verity.actor.itemmanager.ItemCommonType.{ItemContainerEntityId, ItemId, ItemManagerEntityId}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger


class ItemManagerTaskExecutor(val appConfig: AppConfig, val itemManagerEntityId: ItemManagerEntityId)
  extends CoreActorExtended
    with DoNotRecordLifeCycleMetrics {

  val logger: Logger = getLoggerByClass(classOf[ItemManagerTaskExecutor])

  override def receiveCmd: Receive = {
    //if (requestDetailOpt.isEmpty) receiveRequestCmdOnly else receiveRespCmdOnly
    //TODO: come back to this
    receiveRequestCmdOnly orElse receiveRespCmdOnly
  }

  val receiveRequestCmdOnly: Receive = {
    case req: ExecuteAgainstItemContainerLinkedList => processRequest(req)
  }

  val receiveRespCmdOnly: Receive = {
    case resp: ExecuteAndForwardResp => processResponse(resp)
  }

  var requestDetailOpt: Option[RequestDetail] = None

  var responses: Set[ExecuteAndForwardResp] = Set.empty

  def getRequestDetailReq: RequestDetail = requestDetailOpt.getOrElse(throw new RuntimeException("request not yet received"))

  lazy val itemContainerRegion: ActorRef = ClusterSharding(context.system).shardRegion(ITEM_CONTAINER_REGION_ACTOR_NAME)

  def buildGetActiveContainerStatusResponse(): Any = {
    var allContainerIds = Map.empty[ItemContainerEntityId, Map[String, Int]]
    responses.foreach { r =>
      r.resp match {
        case ced: ContainerStatus => allContainerIds = allContainerIds + (ced.id -> ced.items)
      }
    }
    ActiveContainerStatus(allContainerIds)
  }

  def buildGetItemsResponse(): Any = {
    var allItems = Map.empty[ItemId, ItemDetail]
    responses.foreach { r =>
      r.resp match {
        case ced: ContainerItems => allItems = allItems ++ ced.items
      }
    }
    AllItems(allItems)
  }

  def buildRequestForContainerEntity(): Any = {
    getRequestDetailReq.request.cmd match {
      case GetActiveContainers => GetContainerStatus
      case gai: GetItems => gai
    }
  }

  def recordMetrics(): Unit = {
    import com.evernym.verity.metrics.CustomMetrics._
    metricsWriter.gaugeUpdate(AS_USER_AGENT_PAIRWISE_WATCHER_ACTIVE_CONTAINER_COUNT,
      responses.size, Map(TAG_KEY_ID -> itemManagerEntityId))
  }

  def buildAndSendFinalResponseToOriginalSender(): Unit = {
    val resp = getRequestDetailReq.request.cmd match {
      case GetActiveContainers => buildGetActiveContainerStatusResponse()
      case _: GetItems => buildGetItemsResponse()
    }
    getRequestDetailReq.originalSender ! resp
    recordMetrics()
    stopActor()
  }

  def processResponse(lastResponse: ExecuteAndForwardResp): Unit = {
    responses += lastResponse
    if (lastResponse.respFromContainerEntityId == getRequestDetailReq.request.tailContainerId) {
      val updatedTotalExpectedResponse = lastResponse.containerSequenceId
      requestDetailOpt = requestDetailOpt.map(_.copy(totalExpectedResponses = updatedTotalExpectedResponse))
      if (responses.size == getRequestDetailReq.totalExpectedResponses) {
        buildAndSendFinalResponseToOriginalSender()
      }
    }
  }

  def processRequest(req: ExecuteAgainstItemContainerLinkedList): Unit = {
    requestDetailOpt = Option(RequestDetail(sender, 0, req))
    itemContainerRegion ! ForIdentifier(req.headContainerEntityId,
      InternalCmdWrapper(ExecuteAndForwardReq(1, buildRequestForContainerEntity())))
  }

}

case class ExecuteAgainstItemContainerLinkedList(headContainerEntityId: ItemContainerEntityId, tailContainerId: ItemContainerEntityId, cmd: Any) extends ActorMessage
case class ActiveContainerStatus(containers: Map[ItemContainerEntityId, Map[String, Int]]) extends ActorMessage
case class AllItems(items: Map[ItemId, ItemDetail]) extends ActorMessage

/**
 *
 * @param originalSender original sender of the request message
 * @param totalExpectedResponses as this request needs to traverse linked list, this field (totalExpectedResponses) will suggest how many
 *                               total responses it is expecting (it will be equivalent to total containers in the linked list)
 * @param request the request message which needs to be sent to container
 */
case class RequestDetail(originalSender: ActorRef, totalExpectedResponses: Int, request: ExecuteAgainstItemContainerLinkedList)