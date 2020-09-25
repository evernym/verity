package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.protocol.engine.MsgPackVersion
import com.evernym.verity.agentmsg._
import com.evernym.verity.agentmsg.msgcodec.MsgPlusMeta
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgTransformer, AgentMsgWrapper, PackedMsg, UnpackParam}
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID => _, _}
import com.evernym.verity.vault.{EncryptParam, KeyInfo, WalletAPI, WalletAccessParam}

object MsgExtractor {
  type JsonStr = String
  type NativeMsg = Any
}

class MsgExtractor(val keyInfo: KeyInfo, walletAPI: WalletAPI)(implicit wap: WalletAccessParam) {
  import MsgExtractor._

  val amt = new AgentMsgTransformer(walletAPI)

  def unpack(pm: PackedMsg, unpackParam: UnpackParam = UnpackParam()): AgentMsgWrapper = {
    amt.unpack(pm.msg, keyInfo, unpackParam)
  }

  def extract(amw: AgentMsgWrapper)(implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    extract(
      amw.headAgentMsg.msg,
      amw.msgPackVersion,
      amw.msgType
    )
  }

  def extract(amw: AgentMsgWrapper, mpv: MsgPackVersion, mt: MsgType)(implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    extract(
      amw.headAgentMsg.msg,
      mpv,
      mt
    )
  }

  /**
    *
    * @param msg json msg to be mapped to a corresponding native msg
    * @param mpv msg pack version
    * @param mt msg type
    * @param protoReg
    * @return
    */
  def extract(msg: String, mpv: MsgPackVersion, mt: MsgType)
  (implicit protoReg: ProtocolRegistry[_]): MsgPlusMeta = {
    DefaultMsgCodec.decode(msg, mpv, Option(mt))
  }

  def pack(msgPackVersion: MsgPackVersion, json: JsonStr, recipKeys: Set[KeyInfo]): PackedMsg = {
    amt.pack(msgPackVersion, json, EncryptParam(recipKeys, Option(keyInfo)))
  }
}

