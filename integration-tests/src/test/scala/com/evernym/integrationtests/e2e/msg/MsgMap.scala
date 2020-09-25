package com.evernym.integrationtests.e2e.msg

import com.evernym.verity.testkit.agentmsg.MsgBasicDetail

trait MsgMap {
  var msgsByConns: Map[String, Map[String, MsgBasicDetail]] = Map.empty

  def addToMsgs(connId: String, clientUid: String, msgDetail: MsgBasicDetail): Unit = {
    val existingMsgs = msgsByConns.getOrElse(connId, Map.empty)
    val newMsgs = existingMsgs ++ Map(clientUid -> msgDetail)
    msgsByConns += connId -> newMsgs
  }

  def getMsg(connId: String, clientUid: String): Option[MsgBasicDetail] =
    msgsByConns.getOrElse(connId, Map.empty).get(clientUid)

  def getMsgReq(connId: String, clientUid: String): MsgBasicDetail =
    msgsByConns(connId)(clientUid)

  def getMsgUidReq(connId: String, clientUid: String): String = getMsgReq(connId, clientUid).uid
}
