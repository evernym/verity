package com.evernym.verity.integration.legacy

import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.actor.agent.user.UserAgentSpecScaffolding
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.did.didcomm.v1.messages.MsgType
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.protocols.deaddrop.DeadDropSpecUtil
import com.evernym.verity.protocol.protocols.walletBackup.BackupInitParams
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.mock.agent.{MockEdgeAgent, MockEnvUtil}
import com.evernym.verity.testkit.util.Msgs_MFV_0_5
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}
import com.typesafe.config.Config

import java.util.UUID
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext

class WalletBackupActorSpec
  extends UserAgentSpecScaffolding {

  lazy val mockNewEdgeAgent: MockEdgeAgent = MockEnvUtil.buildMockEdgeAgent(mockAgencyAdmin, futureExecutionContext)

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  val walletBackupUtil = new WalletBackupSpecUtil(mockEdgeAgent, futureExecutionContext)

  override def overrideConfig: Option[Config] = Option {
    AkkaTestBasic.customJournal("com.evernym.verity.actor.FailsOnLargeEventTestJournal")
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val dd = setupAgency()

    //this is so that later on when this mock edge agent will have to send a msg
    //to agency agent by encrypting a msg for agency
    mockEdgeAgent.handleFetchAgencyKey(dd)
    mockNewEdgeAgent.handleFetchAgencyKey(dd)
  }

  userAgentBaseSpecs()
  updateComMethodSpecs()

  def alltests(ua: agentRegion, userDIDPair: DidPair): Unit = {

    "An exporter" - {
      initTests()
      uploadingTests()
    }

    "A recoverer" - {
      setupNewEdgeAgent()
      retrieveDeadDrop()
      restoreTests()
    }
  }

  def initTests(): Unit = {
    "when sent init wallet backup message" - {
      "should respond with Done" taggedAs (UNSAFE_IgnoreLog) in {
        val (resp, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val wpm = mockEdgeAgent.v_0_6_req.prepareWalletInitBackupMsgForAgent(walletBackupUtil.backupInitParams, wrapIntoSendMsg = true)
          ua ! wrapAsPackedMsgParam(wpm)
          expectMsg(Done)
        }
        httpMsgOpt.isDefined shouldBe true
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_READY")
      }
    }

    s"when sent GET_MSGS msg (first time)" - {
      "should response with MSGS which includes the new message" in {
        val allMsgs = getMsgs()
        allMsgs.msgs.size shouldBe 1
        allMsgs.msgs.exists(_.`type` == "WALLET_BACKUP_READY") shouldBe true
      }
    }
  }

  def uploadingTests(): Unit = {
    "when sent upload wallet with small wallet" - {
      "should respond with Done" in {
        val (_, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val wpm = mockEdgeAgent.prepareWalletBackupMsg(walletBackupUtil.deadDropData.data)
          ua ! wrapAsPackedMsgParam(wpm)
          expectMsg(Done)
        }
        httpMsgOpt.isDefined shouldBe true
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_ACK")
      }
    }

    "when sent upload wallet a second time with small wallet" - {
      "should respond with Done" in {
        val (_, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val wpm = mockEdgeAgent.prepareWalletBackupMsg(walletBackupUtil.deadDropData.data)
          ua ! wrapAsPackedMsgParam(wpm)
          expectMsg(Done)
        }
        httpMsgOpt.isDefined shouldBe true
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_ACK")
      }
    }

    "when sent wallet bigger than journal max" - {
      "should respond with Done" in {

        val (_, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val wpm = mockEdgeAgent.prepareWalletBackupMsg(Array.range(0, 700000).map(_.toByte))
          ua ! wrapAsPackedMsgParam(wpm)
          expectMsg(Done)
        }
        httpMsgOpt.isDefined shouldBe true
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_ACK")
      }
    }

    "when sent list wallet " - {
      "should respond with Done" in {

        val walletList: List[Int] = List(1, 2, 3, 4)
        val (_, httpMsgOpt) = withExpectNewMsgAtRegisteredEndpoint {
          val wpm = mockEdgeAgent.prepareWalletBackupMsg(walletList)
          ua ! wrapAsPackedMsgParam(wpm)
          expectMsg(Done)
        }
        httpMsgOpt.isDefined shouldBe true
        val agentMsg = mockEdgeAgent.handleReceivedAgentMsg(httpMsgOpt.map(_.msg).get)
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_ACK")
      }
    }

    s"when sent GET_MSGS msg (second time)" - {
      "should response with MSGS which includes the new message" in {
        val allMsgs = getMsgs()
        allMsgs.msgs.size shouldBe 5
        allMsgs.msgs.exists(_.`type` == "WALLET_BACKUP_ACK") shouldBe true
      }
    }
  }

  def setupNewEdgeAgent(): Unit = {
    "when new edge agent is setup" - {
      "should be able to create same key from passphrase" in {
        val nkc = mockNewEdgeAgent.addNewKey(Option(walletBackupUtil.passphrase))
        nkc.verKey shouldBe walletBackupUtil.deadDropData.recoveryVerKey
      }
    }
  }

  def retrieveDeadDrop(): Unit = {
    //NOTE: during recovery, edge agent is sending a RETRIEVE_DEAD_DROP message to agency agent
    //this request is auth crypted by using the recovery key (it can be any other new key as well probably)
    s"when sent RETRIEVE_DEAD_DROP msg" - {
      "should response with the payload" in {
        val msg = mockNewEdgeAgent.prepareGetPayloadMsgForAgent(walletBackupUtil.deadDropData)
        aa ! wrapAsPackedMsgParam(msg)

        //NOTE: edge still doesn't have any way to receive async response messages, so expecting a packed message in synchronous response
        val pm = expectMsgType[PackedMsg]

        //NOTE: the response is unpacked by the same recovery key only (as there is no other key available yet)
        val ddlr = mockNewEdgeAgent.unpackDeadDropLookupResult(pm, walletBackupUtil.backupInitParams.recoveryVk)
        ddlr.entry.isDefined shouldBe true
        val rca = new String(Base64Util.getBase64Decoded(ddlr.entry.get.data))
        rca shouldBe walletBackupUtil.cloudAgentAddress

        walletBackupUtil.setRecoveredCloudAddress(DefaultMsgCodec.fromJson[CloudAgentDetail](rca))
      }
    }
  }

  def restoreTests(): Unit = {
    //NOTE: this message is encrypted with recovery key and sent to cloud agent
    //TODO: this request should use the cloud address received from previous
    "when sent WALLET_BACKUP_RESTORE msg" - {
      "should respond with Done" in {
        val wpm = mockNewEdgeAgent.v_0_6_req.prepareWalletBackupRestoreMsgForAgent(
          walletBackupUtil.backupInitParams, walletBackupUtil.recoveredCloudAddress.verKey)
        ua ! wrapAsPackedMsgParam(wpm)

        //NOTE: client still doesn't have any way to receive async response, so expecting a packed message in synchronous response
        val pm = expectMsgType[PackedMsg]

        //NOTE: the response is unpacked by the same recovery key only (as there is no other key available yet)
        val agentMsg = mockNewEdgeAgent.unpackMsg(pm.msg, Option(walletBackupUtil.backupInitParams.recoveryVk))
        agentMsg.headAgentMsgType shouldBe MsgType(EVERNYM_QUALIFIER, MSG_FAMILY_WALLET_BACKUP, MFV_0_1_0, "WALLET_BACKUP_RESTORED")
      }
    }

    s"when sent GET_MSGS msg (third time)" - {
      "should response with MSGS which includes the new message" in {
        //NOTE: after recovery, new requests are sent as it used to be (with pre-established keys)
        val allMsgs = getMsgs()
        allMsgs.msgs.size shouldBe 5
      }
    }
  }

  def getMsgs(): Msgs_MFV_0_5 = {
    val msg = mockEdgeAgent.v_0_5_req.prepareGetMsgs()
    ua ! wrapAsPackedMsgParam(msg)
    val pm = expectMsgType[PackedMsg]
    mockEdgeAgent.v_0_5_resp.handleGetMsgsResp(pm)
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}

case class CloudAgentDetail(did: DidStr, verKey: VerKeyStr) {
  def didPair: DidPair = DidPair(did, verKey)
}


class WalletBackupSpecUtil(mockEdgeAgent: MockEdgeAgent, executionContext: ExecutionContext) extends DeadDropSpecUtil {

  override def appConfig: AppConfig = mockEdgeAgent.appConfig

  lazy val passphrase = UUID.randomUUID().toString.replace("-", "")

  lazy val cloudAgentAddress = s"""{"did":"${mockEdgeAgent.cloudAgentDetailReq.did}", "verKey": "${mockEdgeAgent.cloudAgentDetailReq.verKey}"}"""

  lazy val deadDropData = prepareDeadDropData(mockEdgeAgent.testWalletAPI, Option(passphrase))(mockEdgeAgent.wap)

  def backupInitParams = BackupInitParams(deadDropData.recoveryVerKey, deadDropData.address, cloudAgentAddress.getBytes())

  var recoveredCloudAddress: CloudAgentDetail = _

  def setRecoveredCloudAddress(cad: CloudAgentDetail) = recoveredCloudAddress = cad

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

