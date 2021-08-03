package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.integrationtests.e2e.env.SdkConfig
import com.evernym.integrationtests.e2e.msg.VcxGetMsg._
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.{Interaction, WalletBackupInteraction, WalletConfigKey}
import com.evernym.integrationtests.e2e.sdk.{BaseSdkProvider, MsgReceiver}
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.sdk.vcx.wallet.WalletApi
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{MsgFamily}
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsMsgFamily
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6
import com.evernym.verity.sdk.protocols.relationship.v1_0.RelationshipV1_0
import com.evernym.verity.sdk.protocols.updateconfigs.v0_6.UpdateConfigsV0_6
import com.evernym.verity.sdk.protocols.updateendpoint.v0_6.UpdateEndpointV0_6
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.{RevocationRegistryConfig, WriteCredentialDefinitionV0_6}
import com.evernym.verity.sdk.protocols.writeschema.v0_6.WriteSchemaV0_6
import com.evernym.verity.sdk.utils.Context
import org.json.{JSONArray, JSONException, JSONObject}
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Interval
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration.Duration
import scala.util.{Success, Try}

protected trait VcxHolds {
  private var interactionMsg: Map[String, Interaction] = Map.empty
  def interaction(key: String): Interaction = interactionMsg(key)
  def updateInteraction(kv: (String, Interaction)): Unit = interactionMsg = interactionMsg + kv

  private var conHandlesMap: Map[DID, Int] = Map.empty
  def connectionHandle(did: DID): Int = conHandlesMap(did)
  def addConnectionHandle(kv: (DID, Int)): Unit = conHandlesMap = conHandlesMap + kv

  private var injectedMsg: Option[JSONObject] = None
  def injectMsg(msg: JSONObject): Unit = injectedMsg = Some(msg)
  def clearInjectedMsg: Option[JSONObject] = {
    val rtn = injectedMsg
    injectedMsg = None
    rtn
  }

  def updateMessageStatus(metaData: VcxMsgMetaData) : Unit = {
    val data = prepareUpdateMessageRequest(metaData.did.get, metaData.msgId)
    UtilsApi.vcxUpdateMessages("MS-106", data).get()
  }

  private def prepareUpdateMessageRequest(pwDid: String, messageUid: String): String = {
    val jsonArray = new JSONArray()
    val request = new JSONObject()
    val uids = new JSONArray()
    uids.put(messageUid)
    request.put("pairwiseDID", pwDid)
    request.put("uids", uids)
    jsonArray.put(request)
    jsonArray.toString
  }
}

case class VcxMsgMetaData(did: Option[DID], senderDid: DID, msgType: String, msgId: String)
case class VcxMsg(msg: JSONObject, meta: VcxMsgMetaData) {

  def payloadMsgType: Option[String] = try {
    if (msg.has("@type")) Option(msg.getString("@type")) else None
  } catch {
    case e: JSONException if e.getMessage.contains("not a string")=>
      Option(msg.getJSONObject("@type").getString("name"))
  }
  def payloadInnerMsg: Try[JSONObject] = Try(new JSONObject(msg.getString("@msg")))
  def payloadInnerMsgType: Option[String] = {
    payloadInnerMsg match {
      case Success(json) if json.has("@type") =>
        Option(try {
          json.getString("@type")
        } catch {
          case e: JSONException if e.getMessage.contains("not a string") =>
            json.getJSONObject("@type").getString("name")
        })
      case _ => None
    }
  }
}

class VcxSdkProvider(val sdkConfig: SdkConfig)
  extends BaseSdkProvider
  with MsgReceiver
  with VcxHolds
  with VcxIssueCredential
  with VcxPresentProof
  with VcxProvision
  with VcxConnecting
  with VcxCommittedAnswer
  with VcxBasicMessage
  with Eventually {

  override def sdkType: String = "VCX"

  lazy val ariesSupportedMsgs = Set(
    ConnectionsMsgFamily.msgType("response")
  ).map(MsgFamily.typeStrFromMsgType)

  def interaction(vcxMsg: VcxMsg): JSONObject = {
    logger.debug("vcxMsg: " + vcxMsg)
    logger.debug("vcxMsg.meta.msgType: " + vcxMsg.meta.msgType)
    logger.debug("vcxMsg.payloadMsgType: " + vcxMsg.payloadMsgType)
    logger.debug("vcxMsg.payloadInnerMsgType: " + vcxMsg.payloadInnerMsgType)
    vcxMsg.meta.msgType match {
      case "question"             => interactQuestion(vcxMsg.meta, vcxMsg.msg)
      case "Question"             => interactQuestion(vcxMsg.meta, vcxMsg.msg)
      case "WALLET_BACKUP_READY"  => new JSONObject().put(`@TYPE`, vcxMsg.meta.msgType)
      case "WALLET_BACKUP_ACK"    => new JSONObject().put(`@TYPE`, vcxMsg.meta.msgType)

      case "aries" if vcxMsg.payloadInnerMsgType.exists(ariesSupportedMsgs.contains)
                                  => vcxMsg.payloadInnerMsg.get
      case "aries" if vcxMsg.payloadInnerMsgType.exists(_.contains("question"))
                                  => interactQuestion(vcxMsg.meta, vcxMsg.msg)
      case "aries" if vcxMsg.payloadInnerMsgType.exists(_.contains("basicmessage"))
                                  => interactMessage(vcxMsg.meta, vcxMsg.msg)
      case "aries" if vcxMsg.payloadMsgType.contains("credential-offer")
                                  => interactCredOffer_1_0(vcxMsg.meta, vcxMsg.msg)
      case "aries" if vcxMsg.payloadMsgType.contains("credential")
                                  => interactCred_1_0(vcxMsg.meta, vcxMsg.msg)
      case "aries" if vcxMsg.payloadMsgType.contains("presentation-request")
                                  => interactProofRequest_1_0(vcxMsg.meta, vcxMsg.msg)
      case _
                                  => throw new Exception("Unknown interaction for VCX")
    }
  }

  override def clean(): Unit = {
    VcxApi.vcxShutdown(true)
  }

  var seenMessage: Set[String] = Set.empty

  private def getAgentMessages(): Seq[VcxMsg] = {
    Try(
      UtilsApi.vcxGetAgentMessages("MS-103", null).get()
    )
      .map(new JSONArray(_))
      .map(arrayToSeq)
      .getOrElse(Seq.empty)
      .map{msg =>
        val meta = VcxMsgMetaData(
          None,
          vcxMessageSender(msg),
          vcxMessageType(msg),
          vcxMessageId(msg)
        )

        VcxMsg(
          new JSONObject(),
          meta
        )
      }
  }

  private def getConnectionMessages(dids: String): Seq[VcxMsg] = {
    Try (
      UtilsApi.vcxGetMessages("MS-103", null, dids).get()
    )
      .map(new JSONArray(_))
      .map(arrayToSeq)
      .getOrElse(Seq.empty)
      .flatMap{ msgList =>
        val receiver = vcxMessageReceiver(msgList)

        arrayToSeq(
          msgList.getJSONArray("msgs")
        )
          .map { msg =>
            val meta = VcxMsgMetaData(
              Some(receiver),
              vcxMessageSender(msg),
              vcxMessageType(msg),
              vcxMessageId(msg)
            )
            VcxMsg(
              new JSONObject(vcxMessageDecryptedPayload(msg)),
              meta
            )
          }
      }
  }

  override def expectMsg(max: Duration): JSONObject = {
    expectMsg(max, Span(1000, Millis))
  }

  def expectMsg(max: Duration, interval: Span): JSONObject = {
    clearInjectedMsg match {
      case Some(m) => m
      case None =>
        Thread.sleep(250)
        eventually(timeout(max), Interval(interval)) {
          val am = getAgentMessages()

          val cm = getConnectionMessages(null)

          val msg = (am ++ cm)
          .find { msg =>
            !seenMessage.contains(msg.meta.msgId)
          }
          .map { msg =>
            seenMessage = seenMessage + msg.meta.msgId
            msg
          }
          assert(msg.isDefined)
          interaction(msg.get)
        }
    }
  }

  def getAllMsgsFromConnection(max: Duration, connectionId: String): Seq[VcxMsg] = {
    Thread.sleep(250)
    eventually(timeout(max), Interval(Span(1000, Millis))) {
      val did = relationship_!(connectionId).owningDID
      val cm = getConnectionMessages(did)
      cm.foreach { msg =>
        seenMessage = seenMessage + msg.meta.msgId
      }
      cm
    }
  }


  // TODO wanted to use version api but java wrapper did not wrap it.
  override def available(): Unit = {}

  override def updateEndpoint_0_6: UpdateEndpointV0_6 =
    unsupportedProtocol("UpdateEndpoint", "Vcx uses a polling model")

  // VCX could support his if we want to implement it
  override def updateConfigs_0_6(name: String, logoUrl: String): UpdateConfigsV0_6 =
    throw new NotImplementedError

  // VCX could support his if we want to implement it
  override def updateConfigs_0_6(): UpdateConfigsV0_6 =
    throw new NotImplementedError


  // VCX don't have this capability, so tests should not use this!
  override def issuerSetup_0_6: IssuerSetupV0_6 = throw new NotImplementedError

  // VCX could support his if we want to implement it
  override def writeSchema_0_6(name: String, version: String, attrs: String*): WriteSchemaV0_6 =
    throw new NotImplementedError

  // VCX could support his if we want to implement it
  override def writeCredDef_0_6(name: String,
                                schemaId: String,
                                tag: Option[String],
                                revocationDetails: Option[RevocationRegistryConfig]): WriteCredentialDefinitionV0_6 =
    throw new NotImplementedError

  def backup(threadId: String, sourceId: String, key: String): Backup = {
    new Backup() {
      def create(context: Context): Unit = {
        logger.debug("id:"+sourceId)
        logger.debug("key:"+key)
        val handle = WalletApi.createWalletBackup(sourceId, key).get()
        updateInteraction( threadId ->
          WalletBackupInteraction(
            key,
            Some(handle)
          )
        )
      }
      def send(context: Context): Unit = {
        val i = interaction(threadId).asInstanceOf[WalletBackupInteraction]
        WalletApi.backupWalletBackup(i.handle.get, "/tmp/walletbackup-"+sourceId).get() // TODO use temp location
      }

      def recover(context: Context): Unit = {
        val config = new JSONObject()
          .put("wallet_name", new JSONObject(context.walletConfig().config()).get("id"))
          .put("wallet_key", new JSONObject(context.walletConfig().credential()).get("key"))
          .put("exported_wallet_path", "/tmp/walletbackup-"+sourceId)
          .put("backup_key", key)

        WalletApi.restoreWalletBackup(config.toString()).get

        val vcxConfig = new JSONObject(
          WalletApi.getRecordWallet(WalletConfigKey, WalletConfigKey, "{}").get()
        ).getString("value")

        VcxApi.vcxShutdown(false)
        VcxApi.vcxInitWithConfig(vcxConfig).get
      }
    }
  }

  override def relationship_1_0(label: String): RelationshipV1_0 = ???

  override def relationship_1_0(forRelationship: String, threadId: String): RelationshipV1_0 = ???

  override def issueCredential_1_0(forRelationship: String,
                                   credDefId: String,
                                   credValues: Map[String, String],
                                   comment: String,
                                   price: String = "0",
                                   autoIssue: Boolean = false,
                                   byInvitation: Boolean = false): IssueCredentialV1_0 = ???

  override def basicMessage_1_0(forRelationship: DID,
                                content: String,
                                sentTime: String,
                                localization: String): BasicMessageV1_0 = ???
}

trait Backup {
  def create(context: Context): Unit
  def send(context: Context): Unit
  def recover(context: Context): Unit
}

object VcxSdkProvider {

  trait Interaction
  case class WalletBackupInteraction(key: String, handle: Option[Int] = None) extends Interaction

  val WalletConfigKey: String = "WalletConfigKey"

}
