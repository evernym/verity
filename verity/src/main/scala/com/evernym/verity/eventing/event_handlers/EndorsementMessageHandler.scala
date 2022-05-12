package com.evernym.verity.eventing.event_handlers

import akka.Done
import com.evernym.verity.actor.agent.msghandler.SendToProtocolActor
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.eventing.event_handlers.EndorsementMessageHandler._
import com.evernym.verity.eventing.event_handlers.RequestSourceUtil.extract
import com.evernym.verity.eventing.ports.consumer.Message
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.MsgEnvelope
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.{CredDefMsgFamily => WriteCredDefMsgFamily0_6, EndorsementResult => WriteCredDefEndorsementResult}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{EndorsementResult => WriteSchemaEndorsementResult, WriteSchemaMsgFamily => WriteSchemaMsgFamily0_6}
import com.typesafe.config.Config
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}

class EndorsementMessageHandler(config: Config,
                                agentMsgRouter: AgentMsgRouter)
                               (implicit executionContext: ExecutionContext){

  private val logger = getLoggerByClass(getClass)

  def handleMessage(message: Message): Future[Done] = {
    val jsonObject = message.cloudEvent
    val eventData = jsonObject.getJSONObject(CLOUD_EVENT_DATA)
    val requestSource = extract(eventData.getString(DATA_FIELD_REQUEST_SOURCE))
    val cmd = createCmd(requestSource, jsonObject)
    sendToRouter(requestSource, cmd)
  }

  private def createCmd(requestSource: RequestSource, jsonObject: JSONObject): Any = {
    requestSource.pinstIdPair.protoDef.msgFamily match {
      case WriteSchemaMsgFamily0_6 => createWriteSchema0_6ControlMsg(jsonObject)
      case WriteCredDefMsgFamily0_6 => createWriteCredDef0_6ControlMsg(jsonObject)
      case other => logger.info("unhandled event request source: " + other)
    }
  }

  private def createWriteSchema0_6ControlMsg(jsonObject: JSONObject): Control = {
    jsonObject.getString(CLOUD_EVENT_TYPE) match {
      case EVENT_ENDORSEMENT_COMPLETE_V1 =>
        val eventData = jsonObject.getJSONObject(CLOUD_EVENT_DATA)
        val result = eventData.getJSONObject(DATA_FIELD_RESULT)
        val resultCode = result.getString(DATA_FIELD_RESULT_CODE)
        val resultDescr = result.getString(DATA_FIELD_RESULT_DESCR)
        WriteSchemaEndorsementResult(resultCode, resultDescr)
    }
  }

  private def createWriteCredDef0_6ControlMsg(jsonObject: JSONObject): Control = {
    jsonObject.getString(CLOUD_EVENT_TYPE) match {
      case EVENT_ENDORSEMENT_COMPLETE_V1 =>
        val eventData = jsonObject.getJSONObject(CLOUD_EVENT_DATA)
        val result = eventData.getJSONObject(DATA_FIELD_RESULT)
        val resultCode = result.getString(DATA_FIELD_RESULT_CODE)
        val resultDescr = result.getString(DATA_FIELD_RESULT_DESCR)
        WriteCredDefEndorsementResult(resultCode, resultDescr)
    }
  }

  private def sendToRouter(requestSource: RequestSource, cmd: Any): Future[Done] = {
    val protoCmd = SendToProtocolActor(
      requestSource.pinstIdPair,
      MsgEnvelope(cmd, null, null, null, msgId = Option(MsgFamilyUtil.getNewMsgUniqueId), thId = Option(requestSource.threadId))
    )
    agentMsgRouter
      .execute(InternalMsgRouteParam(requestSource.relationshipId, protoCmd))
      .map(_ => Done)
  }

  private def buildProtoRef(msgFamily: MsgFamily): ProtoRef = ProtoRef(msgFamily.name, msgFamily.version)
}

object EndorsementMessageHandler {
  //constants
  val DATA_FIELD_ENDORSEMENT_ID = "endorsementid"
  val DATA_FIELD_SUBMITTER_DID = "submitterdid"
  val DATA_FIELD_RESULT = "result"
  val DATA_FIELD_RESULT_CODE = "code"
  val DATA_FIELD_RESULT_DESCR = "descr"
}