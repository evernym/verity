package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.util2.Status.UNAUTHORIZED
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgpacker.AgentMsgParseUtil.convertTo
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.testkit.agentmsg
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.util.Base64Util
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.integration.legacy.{CloudAgentDetail, WalletBackupSpecUtil}
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent


trait WalletBackupAndRecoverySpec { this : EndpointHandlerBaseSpec =>

  import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller

  //NOTE: this test assumes that a valid test push notification com method (search for this val: validTestPushNotif)
  // is already added before this test runs.
  def testWalletBackupAndRecovery(mockEdgeAgent: MockEdgeAgent, mockNewEdgeAgent: MockEdgeAgent): Unit = {

    val walletBackupUtil = new WalletBackupSpecUtil(mockEdgeAgent)

    implicit val msgPackagingContext: AgentMsgPackagingContext =
      agentmsg.AgentMsgPackagingContext(MPF_INDY_PACK, MTV_1_0, packForAgencyRoute = true)

    val test_wallet: Array[Byte] = Array(1, 2, 3, 4, 5, 6, 7, 8, 9)
    val test_wallet_new_large: Array[Byte] = Array.range(0, 700000).map(_.toByte)

    def checkGetMsgsCount(edgeAgent: MockEdgeAgent, count: Int, msgType: Option[String]): Unit = {

      buildAgentPostReq(edgeAgent.prepareGetMsgsForAgent(MTV_1_0).msg) ~> epRoutes ~> check {
        status shouldBe OK

        val gm = edgeAgent.v_0_5_resp.handleGetMsgsResp(PackedMsg(responseAs[Array[Byte]]))
        gm.msgs.size shouldBe count

        if (msgType.isDefined) {
          gm.msgs.exists(_.`type` == msgType.get) shouldBe true
        }
      }
    }

    // TODO: Error handling of this case could be better - message is lost somewhere.
    "when sent a BACKUP without a previously performed init" - {
      "should fail" in {

        val wpm = mockEdgeAgent.prepareWalletBackupMsg(test_wallet)

        buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
          status shouldBe OK
        }

        checkGetMsgsCount(mockEdgeAgent, 0, None)
      }
    }

    "when sent a GET_BACKUP without a previously performed recovery key registration" - {
      "should return a Unauthorized with 'unauthorized' status" in {

        val wpm = mockEdgeAgent.v_0_6_req.prepareWalletBackupRestoreMsgForAgency(
          walletBackupUtil.backupInitParams, mockEdgeAgent.cloudAgentDetailReq.verKey)

        buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          responseAs[String] should include(UNAUTHORIZED.statusCode)
        }
      }
    }

    "when sent a WALLET_BACKUP_INIT" - {
      "should respond with WALLET_BACKUP_READY" in {

        val expected_type = "WALLET_BACKUP_READY"
        val wpm = mockEdgeAgent.v_0_6_req.prepareWalletBackupInitMsgForAgency(walletBackupUtil.backupInitParams)

        val (r, lastPayload) = withExpectNewPushNotif(validTestPushNotifToken, {
          buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
            status shouldBe OK
          }
        })

        lastPayload.isDefined shouldBe true
        lastPayload.get.extraData("forDID") shouldBe mockEdgeAgent.myDIDDetail.did
        lastPayload.get.extraData("type") shouldBe expected_type // legacy
        lastPayload.get.extraData("msgType") shouldBe expected_type

        checkGetMsgsCount(mockEdgeAgent, 1, Option(expected_type))
      }
    }

    "when sent a GET_BACKUP without previously storing one" - {
      "should return a BadRequest with a 'No Wallet Backup available to download' status" in {

        val wpm = mockEdgeAgent.v_0_6_req.prepareWalletBackupRestoreMsgForAgency(
          walletBackupUtil.backupInitParams, mockEdgeAgent.cloudAgentDetailReq.verKey)

        buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
          status shouldBe BadRequest
          responseAs[String] should include("No Wallet Backup available to download")

          checkGetMsgsCount(mockEdgeAgent, 1, None)
        }
      }
    }

    "when sent a WALLET_BACKUP" - {
      "should receive a WALLET_BACKUP_ACK" in {

        val expected_type = "WALLET_BACKUP_ACK"
        val wpm = mockEdgeAgent.prepareWalletBackupMsg(test_wallet)

        val (_, lastPayload) = withExpectNewPushNotif(validTestPushNotifToken, {
          buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
            status shouldBe OK
          }
        })

        lastPayload.isDefined shouldBe true
        lastPayload.get.extraData("forDID") shouldBe mockEdgeAgent.myDIDDetail.did
        lastPayload.get.extraData("type") shouldBe expected_type // legacy
        lastPayload.get.extraData("msgType") shouldBe expected_type

        checkGetMsgsCount(mockEdgeAgent, 2, Option(expected_type))
      }
    }

    "when sent a GET_BACKUP with an existing edge agent" - {
      "should get the wallet we previously stored" in {

        val wpm = mockEdgeAgent.v_0_6_req.prepareWalletBackupRestoreMsgForAgency(
          walletBackupUtil.backupInitParams, mockEdgeAgent.cloudAgentDetailReq.verKey)

        buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          val restoredWalletMsg = mockEdgeAgent.unpackRestoredWalletMsg(responseAs[Array[Byte]])
          restoredWalletMsg.wallet.sameElements(Base64Util.getBase64Encoded(test_wallet)) shouldBe true
        }
      }
    }

    "when sent a new WALLET_BACKUP" - {
      "should receive a WALLET_BACKUP_ACK" in {

        val expected_type = "WALLET_BACKUP_ACK"
        val wpm = mockEdgeAgent.prepareWalletBackupMsg(test_wallet_new_large)

        val (r, lastPayload) = withExpectNewPushNotif(validTestPushNotifToken, {
          buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
            status shouldBe OK
          }
        })

        lastPayload.isDefined shouldBe true
        lastPayload.get.extraData("forDID") shouldBe mockEdgeAgent.myDIDDetail.did
        lastPayload.get.extraData("type") shouldBe expected_type
        lastPayload.get.extraData("msgType") shouldBe expected_type

        checkGetMsgsCount(mockEdgeAgent, 3, Option(expected_type))
      }
    }

    "when new agent is setup" - {
      "should be able to create same key from passphrase" in {

        buildGetReq(s"/agency") ~> epRoutes ~> check {
          status shouldBe OK
          mockNewEdgeAgent.handleFetchAgencyKey(responseTo[AgencyPublicDid])
          val nkc = mockNewEdgeAgent.addNewKey(Option(walletBackupUtil.passphrase))
          nkc.verKey shouldBe walletBackupUtil.deadDropData.recoveryVerKey
        }
      }
    }

    "when restoring content from the dead-drop" - {
      "should get the right data" in {

        val dpm = mockNewEdgeAgent.prepareGetPayloadMsgForAgency(walletBackupUtil.deadDropData)

        buildAgentPostReq(dpm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          val ddlr = mockNewEdgeAgent.unpackDeadDropLookupResult(
            PackedMsg(responseAs[Array[Byte]]), walletBackupUtil.backupInitParams.recoveryVk)
          ddlr.entry.isDefined shouldBe true
          val decoded = Base64Util.getBase64Decoded(ddlr.entry.get.data)
          val rca = new String(decoded)
          rca shouldBe walletBackupUtil.cloudAgentAddress

          walletBackupUtil.setRecoveredCloudAddress(convertTo[CloudAgentDetail](rca))
          mockNewEdgeAgent.setCloudAgentDetail(walletBackupUtil.recoveredCloudAddress.didPair)
        }
      }
    }

    "when sent WALLET_BACKUP_RESTORE" - {
      "should respond with WALLET_BACKUP_RESTORED" in {

        val wpm = mockNewEdgeAgent.v_0_6_req.prepareWalletBackupRestoreMsgForAgency(
          walletBackupUtil.backupInitParams, walletBackupUtil.recoveredCloudAddress.verKey)

        buildAgentPostReq(wpm.msg) ~> epRoutes ~> check {
          status shouldBe OK
          val restoredWalletMsg = mockNewEdgeAgent.unpackRestoredWalletMsg(responseAs[Array[Byte]])
          restoredWalletMsg.wallet.sameElements(Base64Util.getBase64Encoded(test_wallet_new_large)) shouldBe true
        }
      }
    }

  }

}
