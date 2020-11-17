package com.evernym.verity.integration

import java.nio.file.{Path, Paths}

import com.evernym.verity.actor.{DidPair, Platform}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgTransformerApi, ParseParam, UnpackParam}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.protocol.engine.{DID, MPF_MSG_PACK, MsgPackFormat, VerKey}
import com.evernym.verity.protocol.protocols.connecting.common.AgentKeyDlgProof
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.testkit.listener.{Listener, MsgHandler}
import com.evernym.verity.testkit.mock.ledger.InitLedgerData
import com.evernym.verity.util.Base64Util
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.sdk.vcx.vcx.VcxApi
import com.evernym.sdk.vcx.wallet.WalletApi
import com.evernym.verity.integration.veritysdk.TempSender
import com.evernym.verity.sdk.handlers.Handlers
import com.evernym.verity.sdk.protocols.provision.Provision
import com.evernym.verity.sdk.protocols.updateendpoint.UpdateEndpoint
import com.evernym.verity.sdk.transports.{HTTPTransport, Transport}
import com.evernym.verity.sdk.utils.{Context, ContextBuilder, Util => VerityUtil}
import org.hyperledger.indy.sdk.crypto.Crypto
import org.hyperledger.indy.sdk.did.DidResults
import org.json.JSONObject
import org.scalatest.Outcome

import scala.language.postfixOps
import scala.util.Random


private object Seed {
  val node1Ports = PortProfile(9002,2552,8552)
  val node1Seed: String= "58ac7d8396c442e4a5f17652d40e5298"
  val node1DID: DID = "NHCQGrPmZsj6b5Yztoob3r"
  val node1Verkey: VerKey = "CbgPS5xMF4gDi3ihu7nBBLstxZscvwACQWzadZ5gkkdH"

  val node2Ports = PortProfile(9003,2553,8553)
  val node2Seed: String = "3d7fb75981e6475f92b6a189c0dd85b9"
  val node2DID: DID = "MMNddcqVSr9i6pRBMvdnrg"
  val node2Verkey: VerKey = "C6Lv92f7a8aai86QKG1Bqjnr3YkGECYyqqyYKTah9Lgj"

  val ledgerNymMap: Map[String, DidPair] = Map(
    node1DID -> DidPair(node1DID, node1Verkey),
    node2DID -> DidPair(node2DID, node2Verkey)
  )

  val ledgerAttrMap: Map[String, String] = Map(
    s"$node1DID-url" -> s"""http://localhost:${node1Ports.http}/agency/msg""",
    s"$node2DID-url" -> s"""http://localhost:${node2Ports.http}/agency/msg""",
  )

  val ledgerData:InitLedgerData = InitLedgerData(ledgerNymMap, ledgerAttrMap)
}

private object SyncNodes {
  val sendViaFilePath: Path = {
    val rtn = Paths.get(System.getProperty("java.io.tmpdir")).resolve("sendViaFile")
    rtn.toFile.mkdirs()
    rtn
  }
  val inviteDetailPath: Path = sendViaFilePath.resolve("inviteDetail")
}

abstract class RunVerity extends BasicFixtureSpec with TempDir {
  override type FixtureParam = Platform

  def applicationPortProfile:PortProfile
  def applicationSeed: String = Random.alphanumeric.take(32).mkString // This is not cryptographically secure (ONLY FOR TESTING)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val taaEnabled = sys.env.getOrElse("LIB_INDY_LEDGER_TAA_ENABLED", "true").toBoolean
    val taaAutoAccept = sys.env.getOrElse("LIB_INDY_LEDGER_TAA_AUTO_ACCEPT", "true").toBoolean
    val trackMessageProgress = sys.env.getOrElse("MESSAGE_PROGRESS_TRACKING_ENABLED", "false").toBoolean
    val as = LocalVerity(tempDir, applicationPortProfile, applicationSeed, Seed.ledgerData, taaEnabled, taaAutoAccept, trackMessageProgress)
    try {
      withFixture(test.toNoArgTest(as))
    }
    finally {
      //TODO we need to stop the application
    }
  }
}

trait CommonMultiJvmSpecs extends BasicFixtureSpec {
  def listenerPort: Int
  var context: Option[Context] = None

  def `context_!`: Context = context.getOrElse(throw new RuntimeException("context not set"))

  def sendMessageToAgent(msgStr: JSONObject) = {
    //val transport: Transport = new HTTPTransport()
    val msg = VerityUtil.packMessageForVerity(context_!, msgStr)
    TempSender.sendMessage(context_!.verityUrl(), msg)
  }

  def packMsg(mpf: MsgPackFormat, msg: String,
              recipKeys: Set[VerKey], senderVerKey: Option[VerKey]): Array[Byte] = {
    AgentMsgTransformerApi.pack(mpf, `context_!`.walletHandle(), msg, recipKeys, senderVerKey).msg
  }

  def unpackMsg(msg: Array[Byte], fromVerKey: Option[VerKey]): String = {
    val unpackParam = UnpackParam(ParseParam(useInsideMsgIfPresent = true))
    AgentMsgTransformerApi.unpack(`context_!`.walletHandle(), msg, fromVerKey, unpackParam).headAgentMsg.msg
  }

  def initVcx(): Unit = {
    val jsonConfig =
      s"""
        {
         "agency_endpoint": "http://localhost:9003",
         "agency_did": "${`context_!`.verityPublicDID()}",
         "agency_verkey": "${`context_!`.verityPublicVerKey()}",
         "sdk_to_remote_did": "${`context_!`.sdkVerKeyId()}",
         "sdk_to_remote_verkey": "${`context_!`.sdkVerKey()}",
         "institution_did": "SEoN9k5cCNS5Mdk8TANtdD",
         "institution_verkey": "Ekpau7rzrWzyYfnwbfcJGMgiuAKLEKXKeBUqCmYw4nrM",
         "remote_to_sdk_did": "${`context_!`.domainDID()}",
         "remote_to_sdk_verkey": "${`context_!`.verityAgentVerKey()}",
         "institution_name": "name",
         "institution_logo_url": "url",
         "genesis_path": "/home/user/genesis.txt"
        }
      """.stripMargin
    UtilsApi.setPoolHandle(11)  //TODO: we are not using any real pool and hence passed just a non zero number
    WalletApi.setWalletHandle(`context_!`.walletHandle().getWalletHandle)
    VcxApi.vcxInitMinimal(jsonConfig)
  }

}

private object SetupCommon {

  var listener: Listener = _

  def provision(appPort: Int, listenerPort: Int): Context = {
    var ctx = ContextBuilder.fromScratch(
      s"${System.currentTimeMillis()}",
      "WALLETKEY",
      s"http://localhost:${appPort}")
      .toContextBuilder
      .endpointUrl(s"http://localhost:$listenerPort")
      .build()
    val proto = Provision.v0_7()
    ctx = proto.provision(ctx)
    ctx
  }

  def configureWebHook(ctx: Context, handles: Handlers, listenerPort: Int): Unit = {
    println(s"Configuring webhook to endpointUrl: ${ctx.endpointUrl()}")
    listener = new Listener(listenerPort, { encryptMsg =>
      handles.handleMessage(ctx, encryptMsg)
    })
    listener.listen()
    Thread.sleep(100)
    UpdateEndpoint.v0_6().update(ctx)
  }

  //TODO: confirm if this is ok?
  def updateMsgHandler(ctx: Context, handler: MsgHandler): Unit = {
    listener.updateMsgHandler(handler)
  }
}


trait CommonPairwiseSpecs
  extends CommonMultiJvmSpecs {

  var remotePairwiseDID: String = _
  var remotePairwiseVerKey: String = _
  var did: DidResults.CreateAndStoreMyDidResult = _

  def sendMsgToPairwiseByUsingMsgPack(msg: JSONObject): Unit = {
    val agentMsg = packMsg(MPF_MSG_PACK, msg.toString, Set(remotePairwiseVerKey), Option(did.getVerkey))
    val fwd = AgentMsgPackagingUtil.buildFwdJsonMsg(MPF_MSG_PACK, remotePairwiseDID, agentMsg)
    val agencyMsg = packMsg(MPF_MSG_PACK, fwd, Set(context_!.verityPublicVerKey), None)

    sendToTransport(agencyMsg)
  }

  def sendMessageToPairwise(msgStr: JSONObject): Unit = {

    val msg = VerityUtil.packMessageForVerity(
      context_!.walletHandle(),
      msgStr,
      remotePairwiseDID,
      remotePairwiseVerKey,
      did.getVerkey,
      context_!.verityPublicVerKey
    )

    sendToTransport(msg)
  }

  def sendToTransport(msg: Array[Byte]): Unit = {
    val transport: Transport = new HTTPTransport()  //TODO: do we need to create new HTTP transport always?
    transport.sendMessage(context_!.verityUrl, msg)
  }

  def buildKeyDlgProof(signVerKey: VerKey, pairwiseDID: DID, pairwiseVerKey: VerKey): JSONObject = {
    val keyDlgProof = AgentKeyDlgProof(pairwiseDID, pairwiseVerKey, "")
    val sig = Crypto.cryptoSign(context_!.walletHandle, signVerKey, keyDlgProof.buildChallenge.getBytes).get

    val rtn = new JSONObject()
    rtn.put("agentDID", keyDlgProof.agentDID)
    rtn.put("agentDelegatedKey", keyDlgProof.agentDelegatedKey)
    rtn.put("signature", Base64Util.getBase64Encoded(sig))
    rtn
  }
}
