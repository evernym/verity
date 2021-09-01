package com.evernym.verity.http.base

import com.evernym.verity.actor.agent.user.msgstore.MsgDetail

/**
 * utility to store messages (used by specs)
 */
trait HasMsgStore {

  var messageStore: Map[String, Map[String, MsgDetail]] = Map.empty

  def msgKey(typ: String, seqNo: Int): String = typ + "-" + seqNo

  def addToMsgStore(connId: String, md: MsgDetail): Unit = {
    val oldMsgByConn = getMsgsByConn(connId)
    if (! oldMsgByConn.exists(_._2.uid == md.uid)) {
      val oldMsgTypeSeqNo = oldMsgByConn.count(_._2.`type` == md.`type`)
      val newMsgKey = msgKey(md.`type`, oldMsgTypeSeqNo + 1)
      val newMsgByConn = oldMsgByConn ++ Map(newMsgKey -> md)
      messageStore = messageStore ++ Map(connId -> newMsgByConn)
    }
  }

  def addToMsgStore(connId: String, mds: List[MsgDetail]): Unit = mds.foreach(addToMsgStore(connId, _))

  def getMsg(connId: String, typ: String, seqNo: Int): Option[MsgDetail] =
    getMsgsByConn(connId).get(msgKey(typ, seqNo))

  def getLatestMsg(connId: String, typ: String): Option[MsgDetail] = {
    val latestSeqNo = getLatestSeqNo(connId, typ)
    getMsg(connId, typ, latestSeqNo)
  }

  def getLatestMsgReq(connId: String, typ: String): MsgDetail =
    getLatestMsg(connId, typ).getOrElse(
      throw new RuntimeException(s"connection '$connId' doesn't found latest message of type: $typ"))

  private def getLatestSeqNo(connId: String, typ: String): Int = {
    getMsgsByConnAndType(connId, typ).map(_._1.split("-").last.toInt).max
  }

  private def getMsgsByConnAndType(connId: String, typ: String): Map[String, MsgDetail] =
    getMsgsByConn(connId).filter(_._2.`type` == typ)

  private def getMsgsByConn(connId: String): Map[String, MsgDetail] = messageStore.getOrElse(connId, Map.empty)
}
