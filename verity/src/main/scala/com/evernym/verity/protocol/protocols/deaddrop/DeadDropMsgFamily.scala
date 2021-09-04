package com.evernym.verity.protocol.protocols.deaddrop

import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.MSG_TYPE_DEAD_DROP_STORE_DATA
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.engine._

object DeadDropMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "dead-drop"
  override val version: MsgFamilyVersion = "0.1.0"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"      -> classOf[Init],
    "StoreData" -> classOf[StoreData],
    "GetData"   -> classOf[GetData]
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] =  Map (
    "DEAD_DROP_ADD"               -> classOf[Add],
    "DEAD_DROP_RETRIEVE"          -> classOf[Retrieve],
    "DEAD_DROP_RETRIEVE_RESULT"   -> classOf[DeadDropRetrieveResult],
    MSG_TYPE_DEAD_DROP_STORE_DATA -> classOf[StoreData]
  )

}
