package com.evernym.verity.integration.veritysdk

import java.util.UUID

import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.{DID, ThreadId, VerKey}
import com.evernym.verity.util.{MessagePackUtil, MsgIdProvider}
import org.json.{JSONArray, JSONObject}

import scala.collection.JavaConverters._

/*
Util to build messages to send to Verity. The verity-sdk should provide these but there are currently some messages
that don't make sense to provide via the SDK.

Reasons:
1. The messages require edge agent functionally (see Create Key and AcceptInviteionMessage

This file and the message provided here should phase out over time.
 */
object TempMsg {
  val ACCEPT_INVITATION_MESSAGE_TYPE: String = "did:sov:123456789abcdefghi1234;spec/connecting/0.6/ACCEPT_CONN_REQ"
  val CREATE_KEY_MESSAGE_TYPE: String = "did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_KEY"

  def createKeyMessageToString(did: DID, verkey: VerKey): JSONObject = {
    val message = new JSONObject
    message.put("@id", UUID.randomUUID.toString)
    message.put("@type", CREATE_KEY_MESSAGE_TYPE)
    message.put("forDID", did)
    message.put("forDIDVerKey", verkey)
    message
  }

  def acceptInvitationMessageToString(inviteDetails: String, delProof: String): JSONObject = {
    val invite = new JSONObject(inviteDetails)
    val details = invite.getJSONObject("inviteDetail")
    val message = new JSONObject
    message.put("@id", UUID.randomUUID.toString)
    message.put("@type", ACCEPT_INVITATION_MESSAGE_TYPE)
    message.put("sendMsg", true)
    message.put("senderDetail", details.getJSONObject("senderDetail"))
    message.put("senderAgencyDetail", details.getJSONObject("senderAgencyDetail"))
    message.put("replyToMsgId", invite.get("@id"))
    message.put("keyDlgProof", new JSONObject(delProof))
    message
  }

  def buildType_0_5(name: String, ver: String): JSONObject = {
    val typeDetail = new JSONObject
    typeDetail.put("name", name)
    typeDetail.put("ver", ver)
  }

  def createBundledSendMsg_0_5(msg: Array[Byte], msgType: String): JSONObject = {
    val createMsg = new JSONObject
    createMsg.put("@type", buildType_0_5("CREATE_MSG", "1.0"))
    createMsg.put("mtype", msgType)
    createMsg.put("sendMsg", true)
    val createMsgPacked = MessagePackUtil.convertJsonStringToPackedMsg(createMsg.toString)

    val msgDetail = new JSONObject
    msgDetail.put("@type", buildType_0_5("DETAIL", "1.0"))
    msgDetail.put(`@MSG`, msg)
    val msgDetailPacked = MessagePackUtil.convertJsonStringToPackedMsg(msgDetail.toString)

    val bundledMsg = new JSONObject
    bundledMsg.put("bundled", Array(createMsgPacked, msgDetailPacked))
  }

  def buildMsgWrapper_0_5(msgType: String, msg: JSONObject): JSONObject = {
    val wrappedMsg = new JSONObject
    wrappedMsg.put("@type", buildType_0_5(msgType, "1.0"))
    wrappedMsg.put(`@MSG`, msg)
  }
}

object TempTicTacToeMsg {
  val TIC_TAC_TOE_MSG_TYPE_PREFIX: String = "did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5"
  val TIC_TAC_TOE_OFFER_MSG_TYPE: String = s"$TIC_TAC_TOE_MSG_TYPE_PREFIX/OFFER"
  val TIC_TAC_TOE_MOVE_MSG_TYPE: String = s"$TIC_TAC_TOE_MSG_TYPE_PREFIX/MOVE"

  val OFFER_ACCEPT_MSG_TYPE = """"@type":"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/OFFER_ACCEPT""""
  val MOVE_MSG_TYPE = """"@type":"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/MOVE""""

  def addThread(toMsg: JSONObject, thIdOpt: Option[ThreadId]): Unit = {
    thIdOpt.foreach { thId =>
      val thread = new JSONObject
      thread.put("thid", thId)
      toMsg.put("~thread", thread)
    }
  }

  def createOfferMsg(id: ThreadId): JSONObject = {
    val message = new JSONObject
    message.put("@type", TIC_TAC_TOE_OFFER_MSG_TYPE)
    message.put("@id", id)
    message
  }

  def createMoveMsg(value: String, atPosition: String, threadId: Option[ThreadId]): JSONObject = {
    val message = new JSONObject
    message.put("@type", TIC_TAC_TOE_MOVE_MSG_TYPE)
    message.put("@id", MsgIdProvider.getNewMsgId)
    message.put("cv", value)
    message.put("at", atPosition) //we may have to randomize this???
    addThread(message, threadId)
    message
  }
}

object VcxDownloadMsgRespHelper {

  def getPayloads(unpackedResp: String, did: DID): List[String] = {
    val decryptedPayload = getMsgAttribValues(unpackedResp, did, "decryptedPayload")
    decryptedPayload.map(new JSONObject(_).getString(`@MSG`))
  }

  def getMsgAttribValues(unpackedResp: String, did: DID, attribName: String): List[String] = {
    getMsgsByDID(unpackedResp, did).map(_.getString(attribName))
  }

  def getMsgsByDID(unpackedResp: String, did: DID): List[JSONObject] = {
    val msgsByDIDs = parseDownloadedMsgs(unpackedResp)
    val msgsFilteredByDID = msgsByDIDs.filter(_.getString("pairwiseDID") == did)
    val arrayOfMsgs = msgsFilteredByDID.flatMap(m => new JSONArray(m.get("msgs").toString).asScala)
    arrayOfMsgs.map(m => new JSONObject(m.toString))
  }

  def parseDownloadedMsgs(unpackedResp: String): List[JSONObject] = {
    val msgsByDIDs = new JSONArray(unpackedResp)
    msgsByDIDs.asScala.map(jo => new JSONObject(jo.toString)).toList
  }
}