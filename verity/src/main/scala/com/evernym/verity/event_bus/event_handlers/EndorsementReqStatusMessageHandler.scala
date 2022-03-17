package com.evernym.verity.event_bus.event_handlers

import akka.Done
import com.evernym.verity.actor.agent.msghandler.SendToProtocolActor
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.event_bus.RequestSource
import com.evernym.verity.event_bus.event_handlers.RequestSourceBuilder.build
import com.evernym.verity.event_bus.ports.consumer.Message
import com.evernym.verity.protocol.container.actor.ProtocolCmd
import com.typesafe.config.Config
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}

class EndorsementReqStatusMessageHandler(config: Config,
                                         agentMsgRouter: AgentMsgRouter)
                                        (implicit executionContext: ExecutionContext){

  def handleMessage(message: Message): Future[Done] = {
    val jsonObject = message.cloudEvent
    val eventData = jsonObject.getJSONObject(EVENT_DATA)
    val requestSource = build(eventData.getString(DATA_FIELD_REQUEST_SOURCE))
    val cmd = createCmd(jsonObject)
    sendToRouter(requestSource, cmd)
  }

  //TODO: a high level implementation, it needs to be finalized/corrected as per final integration changes
  private def createCmd(jsonObject: JSONObject): Any = {
    jsonObject.getString(EVENT_TYPE) match {
      case TYPE_ENDORSEMENT_COMPLETE =>
        val eventData = jsonObject.getJSONObject(EVENT_DATA)
        val result = eventData.getString(DATA_FIELD_RESULT)
        val resultDescr = eventData.getString(DATA_FIELD_RESULT_DESCR)
        EndorsementCompleted(result, resultDescr)
    }
  }

  private def sendToRouter(requestSource: RequestSource, cmd: Any): Future[Done] = {
    val protoCmd = SendToProtocolActor(requestSource.pinstIdPair, ProtocolCmd(cmd, None))
    agentMsgRouter
      .execute(InternalMsgRouteParam(requestSource.route, protoCmd))
      .map(_ => Done)
  }

  //constants
  val DATA_FIELD_RESULT = "result"
  val DATA_FIELD_RESULT_DESCR = "result_descr"
}

//TODO: below command is declared here on temporary basis until it gets created in protocol side
case class EndorsementCompleted(result: String, resultDescription: String)
