package com.evernym.verity.integration

import java.util.concurrent.atomic.AtomicBoolean

import akka.testkit.TestKit
import com.evernym.verity.Status._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.drivers.TicTacToeAI
import com.evernym.verity.protocol.engine.MPF_MSG_PACK
import com.evernym.verity.protocol.protocols.tictactoe.Board
import com.evernym.verity.protocol.protocols.tictactoe.Board.{CellValue, X}
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.{Move, OfferAccept}
import com.evernym.sdk.vcx.utils.UtilsApi
import com.evernym.verity.integration.veritysdk.{TempMsg, TempTicTacToeMsg, VcxDownloadMsgRespHelper}
import org.json.JSONObject

import scala.concurrent.duration._
import scala.language.postfixOps

class TicTacToeMultiJvmNode1
  extends RunVerity
    with ConnectingSpecs {

  override def applicationPortProfile: PortProfile = Seed.node1Ports
  override def applicationSeed: String = Seed.node1Seed
  override val listenerPort = 4000

  "A Verity Application" - {
    "should be able to play TicTacToe game" in { _ =>
      sendProvisioning(applicationPortProfile.http, listenerPort)
      sendInvitation()
      checkIfInviteAccepted()
      Thread.sleep(20000) // TODO this seems to be required?
    }
  }
}

class TicTacToeMultiJvmNode2
  extends RunVerity
    with ConnectingSpecs
    with TicTacToeSpecs {

  override def applicationPortProfile: PortProfile = Seed.node2Ports
  override def applicationSeed: String = Seed.node2Seed
  override val listenerPort = 4001

  val didGetOfferAccepted = new AtomicBoolean(false)
  val didGetMoveMsg = new AtomicBoolean(false)

  var board = new Board

  //NOTE: this is to handle msg packed msgs (default handler assumes indy packed msgs only)
  def updateMsgHandler(): Unit = {
    SetupCommon.updateMsgHandler(`context_!`, { msg =>
      val umsg = unpackMsg(msg, Option(did.getVerkey))
      println("unpacked received msg: " + umsg)
      if (umsg.contains(TempTicTacToeMsg.OFFER_ACCEPT_MSG_TYPE)) {
        didGetOfferAccepted.set(true)
      } else if (umsg.contains(TempTicTacToeMsg.MOVE_MSG_TYPE)) {
        didGetMoveMsg.set(true)
        val incomingMove = DefaultMsgCodec.fromJson[Move](umsg)
        updateBoard(incomingMove.cellValue, incomingMove.at, "AI")
        sendMove()
      }
    })
  }

  def updateBoard(cv: CellValue, at: String, moveFrom: String): Unit = {
    board = board.updateBoard(cv, at)
    println(s"user board after move applied from '$moveFrom' at '$at' \n" + board.draw)
  }

  def downloadMsg(): Unit = {
    val respMsg = UtilsApi.vcxGetMessages(MSG_STATUS_RECEIVED.statusCode, null, null).get
    val msg = VcxDownloadMsgRespHelper.getPayloads(respMsg, did.getDid).head
    DefaultMsgCodec.fromJson[OfferAccept](msg)
  }

  def sendOffer(): Unit = {
    didGetOfferAccepted.set(false)
    buildAndSendOfferMsg()
    TestKit.awaitCond({didGetOfferAccepted.get()}, 10 seconds)
    downloadMsg()
  }

  def printFinishedGameResult(msg: String): Unit = {
    val result = board.getWinner match {
      case Some(winner) => "Winner(" + winner + ")"
      case None => "Draw"
    }
    println("")
    println("------------------------")
    println("Game result: " + result)
    println("------------------------")
    println("")
  }

  def sendMove(): Unit = {
    val at = TicTacToeAI.chooseCell(board)
    if (! board.isFull) {
      updateBoard(X, at, "user")
    }
    if (! board.isFull && board.getWinner.isEmpty) {
      didGetMoveMsg.set(false)
      buildAndSendMoveMsg("X", at)
      TestKit.awaitCond({didGetMoveMsg.get()}, 10 seconds)
    } else
      printFinishedGameResult("Game result: Draw")
  }

  "A Connect.me Application" - {
    "should be able to play TicTacToe game" in { _ =>
      sendProvisioning(applicationPortProfile.http, listenerPort)
      initVcx()
      acceptInvitation()
      Thread.sleep(5000)  //this is to ensure connection acceptance state is reflected in pairwise agent
      updateMsgHandler()
      sendOffer()
      sendMove()
    }
  }
}


trait TicTacToeSpecs
  extends CommonMultiJvmSpecs
    with CommonPairwiseSpecs { this: RunVerity with ConnectingSpecs =>

  var threadId = Option("111")

  def buildAndSendOfferMsg(): Unit = {
    //packed msg for remote verity edge agent
    val offerMsg = TempMsg.buildMsgWrapper_0_5("MESSAGE", TempTicTacToeMsg.createOfferMsg(threadId.get))
    val packedOfferMsg = packMsg(MPF_MSG_PACK, offerMsg.toString, Set(getRemoteEdgePairwiseVerKey), Option(did.getVerkey))

    //msg for sender's cloud agent
    val sendMsg = TempMsg.createBundledSendMsg_0_5(packedOfferMsg, "general")
    sendMsgToPairwiseByUsingMsgPack(sendMsg)

    Thread.sleep(5000) // TODO this seems to be required?
  }

  def buildAndSendMoveMsg(value: String, atPosition: String): Unit = {
    //packed msg for remote verity edge agent
    val offerMsg = TempMsg.buildMsgWrapper_0_5("MESSAGE", TempTicTacToeMsg.createMoveMsg(value, atPosition, threadId))
    val packedOfferMsg = packMsg(MPF_MSG_PACK, offerMsg.toString, Set(getRemoteEdgePairwiseVerKey), Option(did.getVerkey))

    //msg for sender's cloud agent
    val sendMsg = TempMsg.createBundledSendMsg_0_5(packedOfferMsg, "general")
    sendMsgToPairwiseByUsingMsgPack(sendMsg)

    Thread.sleep(5000) // TODO this seems to be required?
  }

  def getRemoteEdgePairwiseVerKey: String = {
    new JSONObject(inviteDetails).
      getJSONObject("inviteDetail").
      getJSONObject("senderDetail").
      getString("verKey")
  }
}


