//package com.evernym.verity.integration
//
//import java.util.concurrent.atomic.AtomicBoolean
//
//import akka.testkit.TestKit
//import com.evernym.verity.util.SyncViaFile
//import com.evernym.verity.integration.veritysdk.TempMsg
//import com.evernym.verity.sdk.handlers.Handlers
//import com.evernym.verity.sdk.protocols.relationship.Relationship
//import com.evernym.verity.sdk.utils.{Util => VerityUtil}
//import org.hyperledger.indy.sdk.did.Did
//import org.json.JSONObject
//
//import scala.concurrent.duration._
//import scala.language.postfixOps
//
//trait ConnectingSpecs extends CommonMultiJvmSpecs with CommonPairwiseSpecs { this: RunVerity =>
//
//  val didGetInviteDetails = new AtomicBoolean(false)
//  val didGetAcceptAck = new AtomicBoolean(false)
//
//  var inviteDetails: String = _
//
//  def sendProvisioning(appPort: Int, listenerPort: Int): Unit = {
//    context = Some(SetupCommon.provision(appPort, listenerPort))
//  }
//
//  def sendInvitation(): Unit = {
//    SyncViaFile.clearFile(SyncNodes.inviteDetailPath)
//
//    val handles: Handlers = new Handlers()
//    handles.addDefaultHandler({msg: JSONObject =>
//      if (msg.has("inviteDetail")) {
//        println("received invite details: " + msg)
//        didGetInviteDetails.set(true)
//        SyncViaFile.sendViaFile(msg.toString, SyncNodes.inviteDetailPath, Option(10 seconds))
//      } else {
//        println("received invite accept acknowledgement: " + msg)
//        didGetAcceptAck.set(true)
//      }
//    })
//
//    SetupCommon.configureWebHook(context_!, handles, listenerPort)
//
//    // TODO this part of the code broke when we dropped support for connecting 0.6
//    //  this is the beginning of a fix to use connections 1.0 but it is not complete.
//    //  I've remove the use of these multi-jvm test from the CI/CD pipeline for now.
//    val rel = Relationship.v1_0("7a353a9b-4f2e-4c2e-b553-d02d11698800")
//    rel.create(context_!)
//    rel.connectionInvitation(context_!)
//  }
//
//  def acceptInvitation(): Unit = {
//    val handles: Handlers = new Handlers()
//
//    inviteDetails = SyncViaFile.receiveViaFile(SyncNodes.inviteDetailPath, 20 seconds)
//    handles.addDefaultHandler({msg: JSONObject =>
//      println("msg received in original msg handler: " + msg)
//    })
//
//    SetupCommon.configureWebHook(context_!, handles, listenerPort)
//
//    did = Did.createAndStoreMyDid(context_!.walletHandle(), "{}").get()
//
//    val createKeyMsg = TempMsg.createKeyMessageToString(did.getDid, did.getVerkey)
//    val keyCreation = sendMessageToAgent(createKeyMsg)
//    val keyCreationDecrypt = VerityUtil.unpackMessage(context_!, keyCreation.getBytes())
//
//    remotePairwiseDID = keyCreationDecrypt.getString("withPairwiseDID")
//    remotePairwiseVerKey = keyCreationDecrypt.getString("withPairwiseDIDVerKey")
//
//    val keyDlgProof = buildKeyDlgProof(did.getVerkey, remotePairwiseDID, remotePairwiseVerKey)
//
//    val acceptKeyMsg = TempMsg.acceptInvitationMessageToString(inviteDetails, keyDlgProof.toString())
//    sendMessageToPairwise(acceptKeyMsg)
//
//    Thread.sleep(5000) // TODO this seems to be required?
//  }
//
//  def checkIfInviteAccepted(): Unit = {
//    TestKit.awaitCond({didGetInviteDetails.get() && didGetAcceptAck.get()}, 20 seconds)
//  }
//}
//
//class ConnectingMultiJvmNode1
//  extends RunVerity
//    with ConnectingSpecs {
//
//  override def applicationPortProfile: PortProfile = Seed.node1Ports
//  override def applicationSeed: String = Seed.node1Seed
//  override val listenerPort = 4000
//
//  "A Verity Application" - {
//    "should be able to send invitation" in { _ =>
//      SyncViaFile.clearFile(SyncNodes.inviteDetailPath)
//      sendProvisioning(applicationPortProfile.http, listenerPort)
//      sendInvitation()
//      checkIfInviteAccepted()
//    }
//  }
//}
//
//class ConnectingMultiJvmNode2
//  extends RunVerity
//    with ConnectingSpecs {
//
//  override def applicationPortProfile: PortProfile = Seed.node2Ports
//  override def applicationSeed: String = Seed.node2Seed
//  override val listenerPort = 4001
//
//  "A Verity Application" - {
//    "should be able to accept invitation" in { _ =>
//      sendProvisioning(applicationPortProfile.http, listenerPort)
//      acceptInvitation()
//    }
//  }
//}
