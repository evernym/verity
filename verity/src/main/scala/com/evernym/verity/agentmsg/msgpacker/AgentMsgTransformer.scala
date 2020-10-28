package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.vault.WalletExt
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.{MsgPackVersion, TypeFormat}
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.vault._
import org.hyperledger.indy.sdk.wallet.Wallet

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext


class AgentMsgTransformer(val walletAPI: WalletAPI) {

  def pack(msgPackVersion: MsgPackVersion, msg: String, encryptInfo: EncryptParam,
           packParam: PackParam = PackParam())
          (implicit wap: WalletAccessParam): PackedMsg = {
    walletAPI.executeOpWithWalletInfo("auth/anon crypt",
      openWalletIfNotExists = packParam.openWalletIfNotOpened, { we: WalletExt =>
        val senderKeyOpt = encryptInfo.senderKey.map({ sk =>
          val future = walletAPI.getVerKeyFromWallet(sk.verKeyDetail)(we)
          Await.result(future, Duration.Inf) // fixme ve2028 testing
        })
        val future = walletAPI.getVerKeyFromWallet(encryptInfo.recipKeys.head.verKeyDetail)(we)
        val verkey = Await.result(future, Duration.Inf) // fixme ve2028 testing
        val recipKeys = Set(verkey)
        AgentMsgTransformerApi.pack(msgPackVersion, we.wallet, msg, recipKeys, senderKeyOpt)
      }
    )
  }

  def unpack(msg: Array[Byte], fromKeyInfo: KeyInfo, unpackParam: UnpackParam = UnpackParam())
            (implicit wap: WalletAccessParam): AgentMsgWrapper = {
    walletAPI.executeOpWithWalletInfo("auth/anon decrypt",
      openWalletIfNotExists = unpackParam.openWalletIfNotOpened, { we: WalletExt =>
        val fromVerKeyFuture = walletAPI.getVerKeyFromWallet(fromKeyInfo.verKeyDetail)(we)
        val fromVerKey = Await.result(fromVerKeyFuture, Duration.Inf) // fixme ve2028 testing
        AgentMsgTransformerApi.unpack(we.wallet, msg, Option(fromVerKey), unpackParam)
      }
    )
  }

  def unpackAsync(msg: Array[Byte], fromKeyInfo: KeyInfo, unpackParam: UnpackParam = UnpackParam())
                 (implicit wap: WalletAccessParam): Future[AgentMsgWrapper] = {
    walletAPI.executeOpWithWalletInfo("auth/anon decrypt",
      openWalletIfNotExists = unpackParam.openWalletIfNotOpened, { we: WalletExt =>
        val fromVerKeyFuture = walletAPI.getVerKeyFromWallet(fromKeyInfo.verKeyDetail)(we)
        fromVerKeyFuture.map({ fromVerKey =>
          AgentMsgTransformerApi.unpack(we.wallet, msg, Option(fromVerKey), unpackParam)
        })
      }
    )
  }

}

case class PackedMsg(msg: Array[Byte], metadata: Option[PayloadMetadata]=None) extends ActorMessageClass

/**
 * wrapper around one decrypted agent message
 * @param msg
 * @param msgFamilyDetail
 * @param msgTypeFormat
 */
case class AgentMsg(msg: String, msgFamilyDetail: MsgFamilyDetail, msgTypeFormat: TypeFormat) {
  def convertTo[T: ClassTag]: T = {
    AgentMsgParseUtil.convertTo[T](msg)
  }
}

/**
 * wrapper to hold decrypted complete agent message (usually, it should be one, but legacy message used a
 * bundled wrapper which can contain more than one message)
 * @param msgs
 * @param senderVerKey
 * @param recipVerKey
 * @param legacyMsgFamily
 * @param usesLegacyGenMsgWrapper
 * @param usesLegacyBundledMsgWrapper
 */
case class AgentBundledMsg(msgs: List[AgentMsg],
                           senderVerKey: Option[VerKey],
                           recipVerKey: Option[VerKey],
                           legacyMsgFamily: Option[MsgFamilyDetail]=None,
                           usesLegacyGenMsgWrapper: Boolean=false,
                           usesLegacyBundledMsgWrapper: Boolean=false) {
  def headAgentMsg: AgentMsg = msgs.head
  def tailAgentMsgs: List[AgentMsg] = msgs.tail
}

case class AgentMsgTypeDetail(msgPackVersion: MsgPackVersion,
                              familyQualifier: MsgFamilyQualifier,
                              familyName: MsgFamilyName,
                              familyVersion: MsgFamilyVersion,
                              msgName: MsgFamilyName,
                              msgVer: Option[String]=None) {

  def typeDetail: TypeDetail = TypeDetail(msgName, msgVer.getOrElse(familyVersion))
  def msgType: MsgType = MsgType(familyQualifier, familyName, familyVersion, msgName)
  def msgVersion: String = msgVer.getOrElse(familyVersion)
}



case class PackParam(openWalletIfNotOpened: Boolean=false)

//NOTE: few parameters in this case class is mainly to handle 0.5 version of agent messages
//so, it is for backward compatibility and in future we'll remove it
case class UnpackParam(parseParam: ParseParam=ParseParam(),
                       openWalletIfNotOpened: Boolean=false,
                       isAnonCryptedMsg: Boolean=false)

/**
  *
  * @param parseBundledMsgs determines if it needs to parse older style of bundled messages
  * @param useInsideMsgIfPresent determines if it needs to parse msg payload provided under @msg json attribute
  *                                this was one off change to support how libvcx currently constructs message
  *                                for other entity's edge agent
  */
case class ParseParam(parseBundledMsgs: Boolean = true, useInsideMsgIfPresent: Boolean = false)

case class UnpackedMsg(msg: String,
                       senderVerKey: Option[VerKey],
                       recipVerKey: Option[VerKey])

//NOTE: origDetailOpt in MsgFamilyDetail case class is the original un parsed detail
// for example: did:sov:123456789abcdefghi1234;spec/onboarding/1.0/CONNECT
// 'msgVer' is only for backward compatibility
case class MsgFamilyDetail(familyQualifier: MsgFamilyQualifier,
                           familyName: MsgFamilyName,
                           familyVersion: MsgFamilyVersion,
                           msgName: MsgName,
                           msgVer: Option[String],
                           isLegacyMsg: Boolean = false
                          ) {
  def getTypeDetail: TypeDetail = TypeDetail(msgName, familyVersion)

  def msgType: MsgType = MsgType(familyQualifier, familyName, familyVersion, msgName)
}

/**
 * an interface to pack and unpack a message
 *
 */
trait MsgTransformer {

  def msgPackVersion: MsgPackVersion

  def pack(wallet: Wallet, msg: String, recipVerKeys: Set[VerKey], senderVerKey: Option[VerKey], packParam: PackParam): PackedMsg

  def unpack(wallet: Wallet, msg: Array[Byte], fromVerKey: Option[VerKey], unpackParam: UnpackParam): AgentBundledMsg
}
