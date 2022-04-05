package com.evernym.verity.event_bus.event_handlers

import akka.Done
import com.evernym.verity.actor.agent.msghandler.SendToProtocolActor
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.event_bus.event_handlers.EndorsementMessageHandler._
import com.evernym.verity.event_bus.event_handlers.RequestSourceUtil.extract
import com.evernym.verity.event_bus.ports.consumer.Message
import com.evernym.verity.protocol.container.actor.MsgEnvelope
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.EndorsementResult
import com.typesafe.config.Config
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}

class EndorsementMessageHandler(config: Config,
                                agentMsgRouter: AgentMsgRouter)
                               (implicit executionContext: ExecutionContext){

  def handleMessage(message: Message): Future[Done] = {
    val jsonObject = message.cloudEvent
    val eventData = jsonObject.getJSONObject(CLOUD_EVENT_DATA)
    val requestSource = extract(eventData.getString(DATA_FIELD_REQUEST_SOURCE))
    val cmd = createCmd(jsonObject)
    sendToRouter(requestSource, cmd)
  }

  private def createCmd(jsonObject: JSONObject): Any = {
    jsonObject.getString(CLOUD_EVENT_TYPE) match {
      case EVENT_ENDORSEMENT_COMPLETE_V1 =>
        val eventData = jsonObject.getJSONObject(CLOUD_EVENT_DATA)
        val result = eventData.getJSONObject(DATA_FIELD_RESULT)
        val resultCode = result.getString(DATA_FIELD_RESULT_CODE)
        val resultDescr = result.getString(DATA_FIELD_RESULT_DESCR)
        EndorsementResult(resultCode, resultDescr)
    }
  }

  private def sendToRouter(requestSource: RequestSource, cmd: Any): Future[Done] = {
    val protoCmd = SendToProtocolActor(
      requestSource.pinstIdPair,
      MsgEnvelope(cmd, null, null, null, msgId = Option(MsgFamilyUtil.getNewMsgUniqueId), thId = Option(requestSource.threadId))
    )
    agentMsgRouter
      .execute(InternalMsgRouteParam(requestSource.route, protoCmd))
      .map(_ => Done)
  }
}

object EndorsementMessageHandler {
  //constants
  val DATA_FIELD_ENDORSEMENT_ID = "endorsementid"
  val DATA_FIELD_SUBMITTER_DID = "submitterdid"
  val DATA_FIELD_RESULT = "result"
  val DATA_FIELD_RESULT_CODE = "code"
  val DATA_FIELD_RESULT_DESCR = "descr"
}