package com.evernym.verity.testkit.util

import java.util.UUID
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.agentmsg.msgfamily.pairwise.PairwiseMsgUids
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.agentmsg.msgfamily.configs.ComMethodPackaging
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgTransformer, FwdRouteMsg, PackMsgParam}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, RequesterKeys}
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, InviteDetail, SenderAgencyDetail, SenderDetail}
import com.evernym.verity.vault._
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.observability.metrics.NoOpMetricsWriter

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

case class TypedMsg(`@type`: TypeDetail)

case class Connect_MFV_0_5(`@type`: TypeDetail, fromDID: DidStr, fromDIDVerKey: VerKeyStr)

case class Connect_MFV_0_6(`@type`: String, fromDID: DidStr, fromDIDVerKey: VerKeyStr)

case class SignUp_MFV_0_5(`@type`: TypeDetail)

case class CreateAgent_MFV_0_5(`@type`: TypeDetail)

case class CreateAgent_MFV_0_6(`@type`: String, fromDID: DidStr, fromDIDVerKey: VerKeyStr)

case class CreateAgent_MFV_0_7(`@type`: String,
                               requesterKeys: RequesterKeys,
                               provisionToken: Option[ProvisionToken],
                               `~thread`: Option[Thread] = Option(
                                 Thread(Option(UUID.randomUUID().toString))))

case class CreateEdgeAgent_MFV_0_7(`@type`: String,
                                   requesterVk: VerKeyStr,
                                   provisionToken: Option[ProvisionToken],
                                   `~thread`: Option[Thread] = Option(
                                     Thread(Option(UUID.randomUUID().toString))))

case class CreateKey_MFV_0_5(`@type`: TypeDetail, forDID: DidStr, forDIDVerKey: VerKeyStr)

case class CreateKey_MFV_0_6(`@type`: String, forDID: DidStr, forDIDVerKey: VerKeyStr)

case class CreateConnection_MFV_0_6(`@type`: String, sourceId: String, phoneNo: Option[String]=None)

case class SendRemoteMsg_MFV_0_6(`@type`: String,
                                 `@id`: String,
                                 mtype: String,
                                 `@msg`: Array[Byte],
                                 sendMsg: Boolean,
                                 `~thread`: Option[Thread] = None,
                                 title: Option[String] = None,
                                 detail: Option[String] = None,
                                 replyToMsgId: Option[String]=None)

case class CreateMsg_MFV_0_5(`@type`: TypeDetail, mtype: String,
                             uid: Option[String] = None,
                             replyToMsgId: Option[String] = None,
                             sendMsg: Boolean = false)

case class ConnReq_MFV_0_6(
                                   `@type`: String,
                                   `@id`: String,
                                   sendMsg: Boolean = false,
                                   includePublicDID: Boolean = false,
                                   `~thread`: Thread,
                                   keyDlgProof: Option[AgentKeyDlgProof] = None,
                                   targetName: Option[String] = None,
                                   phoneNo: Option[String] = None)

case class AcceptConnReq_MFV_0_6(`@type`: String,
                                 `@id`: String,
                                 sendMsg: Boolean = false,
                                 keyDlgProof: AgentKeyDlgProof,
                                 senderDetail: SenderDetail,
                                 senderAgencyDetail: SenderAgencyDetail,
                                 replyToMsgId: String,
                                 `~thread`: Thread)

case class SendMsgs_MFV_0_5(`@type`: TypeDetail, uids: List[String])

case class GetMsg_MFV_0_5(`@type`: TypeDetail,
                          excludePayload: Option[String],
                          uids: Option[List[String]] = None,
                          statusCodes: Option[List[String]] = None)

case class GetMsgsByConns_MFV_0_5(`@type`: TypeDetail,
                                  pairwiseDIDs: Option[List[String]]=None,
                                  excludePayload: Option[String] = None,
                                  uids: Option[List[String]] = None,
                                  statusCodes: Option[List[String]] = None)

case class GetMsgsByConns_MFV_0_6(`@type`: String,
                                  pairwiseDIDs: Option[List[String]]=None,
                                  excludePayload: Option[String] = None,
                                  uids: Option[List[String]] = None,
                                  statusCodes: Option[List[String]] = None)

case class UpdateMsgStatus_MFV_0_5(`@type`: TypeDetail, uids: List[String], statusCode: String)

case class UpdateMsgStatusByConns_MFV_0_5(`@type`: TypeDetail,
                                          statusCode: String,
                                          uidsByConns: List[PairwiseMsgUids])

case class UpdateConnStatus_MFV_0_5(`@type`: TypeDetail, statusCode: String)

case class UpdateConnStatus_MFV_0_6(`@type`: String, statusCode: String)

case class TestConfigDetail(name: String, value: Option[String] = None)

case class UpdateConfigs_MFV_0_5(`@type`: TypeDetail, configs: Set[TestConfigDetail])

case class RemoveConfigs_MFV_0_5(`@type`: TypeDetail, configs: Set[String])

case class GetConfigs_MFV_0_5(`@type`: TypeDetail, configs: Set[String])

case class TestComMethod (id: String, `type`: Int, value: Option[String], packaging: Option[ComMethodPackaging]=None)

case class UpdateComMethod_MFV_0_5(`@type`: TypeDetail, comMethod: TestComMethod)

case class UpdateComMethod_MFV_0_6(`@type`: String, comMethod: TestComMethod)

case class IssuerSetupCreate_MFV_0_6(`@type`: String)

//response msgs
case class Connected_MFV_0_5(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr)

case class SignedUp_MFV_0_5()

case class AgentCreated_MFV_0_5(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr) {
  def didPair = DidPair(withPairwiseDID, withPairwiseDIDVerKey)
}

case class AgentCreated_MFV_0_6(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr) {
  def didPair = DidPair(withPairwiseDID, withPairwiseDIDVerKey)
}

case class AgentCreated_MFV_0_7(selfDID: DidStr, agentVerKey: VerKeyStr)


case class CreateAgentProblemReport_MFV_0_7(msg: String)

case class KeyCreated_MFV_0_5(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr)

case class KeyCreated_MFV_0_6(withPairwiseDID: DidStr, withPairwiseDIDVerKey: VerKeyStr)

case class MsgCreated_MFV_0_5(uid: MsgId)

case class MsgStatusUpdated_MFV_0_5(uids: List[MsgId], statusCode: String)

case class PairwiseError(pairwiseDID: DidStr, statusCode: String, statusMsg: String)

case class MsgStatusUpdatedByConns_MFV_0_5(updatedUidsByConns: List[PairwiseMsgUids],
                                           failed: Option[List[PairwiseError]]=None)

case class InviteMsgDetail_MFV_0_5(inviteDetail: InviteDetail, urlToInviteDetail: String,
                                   urlToInviteDetailEncoded: String)

case class MsgsSent_MFV_0_5(uids: List[String])

case class Msgs_MFV_0_5(msgs: List[MsgDetail])

case class MsgsByConns(pairwiseDID: DidStr, msgs: List[MsgDetail])

case class MsgsByConns_MFV_0_5(msgsByConns: List[MsgsByConns])

case class MsgsByConns_MFV_0_6(msgsByConns: List[MsgsByConns])

case class ConfigsUpdated_MFV_0_5()

case class ComMethodUpdated_MFV_0_5(id: String)

case class ComMethodUpdated_MFV_0_6(id: String)

case class ConnStatusUpdated_MFV_0_5(statusCode: String)

case class ConfigsRemoved_MFV_0_5()

case class ConfigsMsg_MFV_0_5(configs: Set[TestConfigDetail])

case class ConnReqAccepted_MFV_0_6(`@id`: String)

case class PublicIdentifier(did: DidStr, verKey: VerKeyStr)

case class PublicIdentifierCreated_MFV_0_6(`@id`: String, identifier: PublicIdentifier)

object AgentPackMsgUtil {

  def apply(msg: Any, encryptParam: EncryptParam)(implicit mpf: MsgPackFormat): PackMsgParam = {
    val nativeMsgs = msg match {
      case msgs: List[Any]  => msgs
      case native           => List(native)
    }
    PackMsgParam(encryptParam, nativeMsgs, mpf==MPF_MSG_PACK)
  }

  def preparePackedRequestForAgent(agentMsgParam: PackMsgParam)
                                  (implicit msgPackFormat: MsgPackFormat,
                                   agentMsgTransformer: AgentMsgTransformer, wap: WalletAPIParam): PackedMsg = {
    awaitResult(AgentMsgPackagingUtil.buildAgentMsg(msgPackFormat, agentMsgParam)(
      agentMsgTransformer, wap, NoOpMetricsWriter()))
  }

  def preparePackedRequestForRoutes(fwdMsgTypeVersion: String,
                                    packMsgParam: PackMsgParam,
                                    fwdRoutes: List[FwdRouteMsg])
                                   (implicit msgPackFormat: MsgPackFormat,
                                    agentMsgTransformer: AgentMsgTransformer,
                                    wap: WalletAPIParam,
                                    executionContext: ExecutionContext): PackedMsg = {
    awaitResult(
      AgentMsgPackagingUtil.buildRoutedAgentMsgFromPackMsgParam(
        msgPackFormat,
        packMsgParam,
        fwdRoutes,
        fwdMsgTypeVersion
      )(
        agentMsgTransformer,
        wap,
        NoOpMetricsWriter(),
        executionContext
      )
    )
  }

  def awaitResult(fut: Future[PackedMsg]): PackedMsg = {
    Await.result(fut, 5.seconds)
  }
}
