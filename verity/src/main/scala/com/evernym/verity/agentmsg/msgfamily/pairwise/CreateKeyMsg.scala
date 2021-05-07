package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{DID, MsgBase, VerKey}

case class CreateKeyReqMsg_MFV_0_5(forDID: DID, forDIDVerKey: VerKey) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("forDID", forDID)
    checkRequired("forDIDVerKey", forDIDVerKey)
  }
}

case class CreateKeyReqMsg_MFV_0_6(forDID: DID, forDIDVerKey: VerKey) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("forDID", forDID)
    checkRequired("forDIDVerKey", forDIDVerKey)
  }
}

case class CreateKeyReqMsg(forDID: DID, forDIDVerKey: VerKey) {
  def didPair: DidPair = DidPair(forDID, forDIDVerKey)
}

case class KeyCreatedRespMsg_MFV_0_5(`@type`: TypeDetail, withPairwiseDID: DID, withPairwiseDIDVerKey: VerKey) extends MsgBase

case class KeyCreatedRespMsg_MFV_0_6(`@type`: String, withPairwiseDID: DID, withPairwiseDIDVerKey: VerKey) extends MsgBase


object CreateKeyMsgHelper extends MsgHelper[CreateKeyReqMsg] {

  def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): CreateKeyReqMsg = {
    val msg = amw.headAgentMsg.convertTo[CreateKeyReqMsg_MFV_0_5]
    CreateKeyReqMsg(msg.forDID, msg.forDIDVerKey)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): CreateKeyReqMsg = {
    val msg = amw.headAgentMsg.convertTo[CreateKeyReqMsg_MFV_0_6]
    CreateKeyReqMsg(msg.forDID, msg.forDIDVerKey)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): CreateKeyReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("create key req builder failed: " + x)
    }
  }

  private def buildKeyCreatedResp_MFV_0_5(withDID: DID, withDIDVerKey: VerKey): KeyCreatedRespMsg_MFV_0_5 = {
    KeyCreatedRespMsg_MFV_0_5(TypeDetail(MSG_TYPE_KEY_CREATED, MTV_1_0), withDID, withDIDVerKey)
  }

  def buildKeyCreatedResp_MFV_0_6(withDID: DID, withDIDVerKey: VerKey): KeyCreatedRespMsg_MFV_0_6  = {
    KeyCreatedRespMsg_MFV_0_6(MSG_TYPE_DETAIL_KEY_CREATED, withDID, withDIDVerKey)
  }

  def buildRespMsg(pairwiseDID: DID, pairwiseDIDVerKey: VerKey)
                  (implicit respMsgParam: AgentMsgContext): List[Any] = {
    respMsgParam.familyVersion match {
      case MFV_0_5 => List(buildKeyCreatedResp_MFV_0_5(pairwiseDID, pairwiseDIDVerKey))
      case MFV_0_6 => List(buildKeyCreatedResp_MFV_0_6(pairwiseDID, pairwiseDIDVerKey))
      case x => throw new RuntimeException("create agent response builder failed: " + x)
    }
  }
}

