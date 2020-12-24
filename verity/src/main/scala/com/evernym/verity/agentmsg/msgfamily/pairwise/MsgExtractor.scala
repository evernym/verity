package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.agentmsg._
import com.evernym.verity.agentmsg.msgcodec.MsgPlusMeta
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgTransformer, AgentMsgWrapper, UnpackParam}
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID => _, _}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{EncryptParam, KeyInfo, WalletAPIParam}

object MsgExtractor {
  type JsonStr = String
  type NativeMsg = Any
}

class MsgExtractor(val keyInfo: KeyInfo, walletAPI: WalletAPI)(implicit wap: WalletAPIParam) {
  import MsgExtractor._

  val amt = new AgentMsgTransformer(walletAPI)

  def unpack(pm: PackedMsg, unpackParam: UnpackParam = UnpackParam()): AgentMsgWrapper = {
    amt.unpack(pm.msg, keyInfo, unpackParam)
  }

  def extract(amw: AgentMsgWrapper)(implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    extract(
      amw.headAgentMsg.msg,
      amw.msgPackFormat,
      amw.msgType
    )
  }

  def extract(amw: AgentMsgWrapper, mpf: MsgPackFormat, mt: MsgType)(implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    extract(
      amw.headAgentMsg.msg,
      mpf,
      mt
    )
  }

  /**
    *
    * @param msg json msg to be mapped to a corresponding native msg
    * @param mpf msg pack format
    * @param mt msg type
    * @param protoReg
    * @return
    */
  def extract(msg: String, mpf: MsgPackFormat, mt: MsgType)
  (implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    DefaultMsgCodec.decode(msg, mpf, Option(mt))
  }

  def pack(msgPackFormat: MsgPackFormat, json: JsonStr, recipKeys: Set[KeyInfo]): PackedMsg = {
    amt.pack(msgPackFormat, json, EncryptParam(recipKeys, Option(keyInfo)))
  }
}

