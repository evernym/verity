package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.drivers.TicTacToeAI
import com.evernym.verity.protocol.{CtlEnvelope, engine}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.util.{CryptoFunctions, SimpleLogger, SimpleLoggerLike}
import com.evernym.verity.protocol.protocols.tictactoe.Board.X
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.{protoRef, _}
import com.evernym.verity.protocol.protocols.tictactoe.{Board, State, TicTacToeProtoDef}
import com.evernym.verity.util.intTimes
import org.scalatest.concurrent.Eventually
import com.evernym.verity.testkit.BasicSpec


import scala.concurrent.Future

class ProtocolEngineLiteSpec extends BasicSpec with Eventually {
  val logger: SimpleLoggerLike = SimpleLogger(classOf[ProtocolEngineLiteSpec])

  val threadId = "0"

  "ProtocolEngineLite" - {
    "should be able to start the TicTacToe protocol" in {
      val protoRef = ProtoRef("TicTacToe", "0.5")

      val controllerProvider = () => new Driver {
        override def signal[A]: SignalHandler[A] = {
          case SignalEnvelope(_: AskAccept, _, _, _, _) => Some(AcceptOffer())
          case SignalEnvelope(_, _, _, _, _) => None
        }
      }

      val recordsEvents = new SimpleEventRecorder(TicTacToeProtoDef.initialState)
      val recordsEventsProvider = () => recordsEvents

      val aliceSendsMsgs = buildSendsMsgs("0")
      val bobSendsMsgs = buildSendsMsgs("1")

      val cryptoFunctions = new CryptoFunctions {
        override def sha256(input: Array[Byte]): Array[Byte] = CryptoFunctions.sha256(input)
      }

      val aliceEngine = new ProtocolEngineLite(aliceSendsMsgs, cryptoFunctions, logger)
      val bobEngine = new TestProtocolEngine("bob", bobSendsMsgs, cryptoFunctions, logger)
      aliceSendsMsgs.setOutbox(bobEngine.inbox)
      aliceEngine.register(TicTacToeProtoDef, controllerProvider, recordsEventsProvider, None)

      val ctlEnvelope = CtlEnvelope(MakeOffer(), "msgId", threadId)
      aliceEngine.handleMsg("toDID", "fromDID", threadId, protoRef, ctlEnvelope)
    }

    "should be able to run the TicTacToe protocol" in {
      val aliceDID = "alice DID"
      val bobDID = "bob DID"
      val protoRef = ProtoRef("TicTacToe", "0.5")

      val recordsEventsProvider = () => new SimpleEventRecorder(TicTacToeProtoDef.initialState)

      val cryptoFunctions = new CryptoFunctions {
        override def sha256(input: Array[Byte]): Array[Byte] = CryptoFunctions.sha256(input)
      }

      20 times { // Simulate 20 TicTacToe games
        var complete = false

        val controllerProvider = () => new Driver {
          override def signal[A]: SignalHandler[A] = {
            case SignalEnvelope(_: AskAccept, _, _, _, _)   => Some(AcceptOffer())
            case SignalEnvelope(m: YourTurn, _, _, _, _)    =>
              if(m.state.isInstanceOf[State.Finished]) {
                None
              } else {
                Some(MakeMove(Board.getOtherCellValue(m.value), TicTacToeAI.chooseCell(m.state.asInstanceOf[State.Playing].game.board)))
              }
            case SignalEnvelope(m: DeclareDraw, _, _, _, _) =>
              complete = true
              logger.debug("Draw")
              logger.debug(m.board.draw)
              None

            case SignalEnvelope(m: DeclareWinner, _, _, _, _) =>
              complete = true
              logger.debug("Winner")
              logger.debug(m.board.draw)
              None

            case SignalEnvelope(_, _, _, _, _)=> None
          }
        }

        val aliceSendsMsgs = buildSendsMsgs(threadId)
        val aliceEngine = new TestProtocolEngine("alice", aliceSendsMsgs, cryptoFunctions, logger)
        aliceEngine.register(TicTacToeProtoDef, controllerProvider, recordsEventsProvider, None)

        val bobSendsMsgs = buildSendsMsgs(threadId)
        val bobEngine = new TestProtocolEngine("bob", bobSendsMsgs, cryptoFunctions, logger)
        bobEngine.register(TicTacToeProtoDef, controllerProvider, recordsEventsProvider, None)

        aliceSendsMsgs.setOutbox(bobEngine.inbox)
        bobSendsMsgs.setOutbox(aliceEngine.inbox)

        val ctlEnvelope1 = CtlEnvelope(MakeOffer(), "msgId", threadId)

        aliceEngine.handleMsg(aliceDID, bobDID, threadId, protoRef, ctlEnvelope1)
        aliceEngine.processAllBoxes()
        bobEngine.inbox.process() // Handle Offer message
        bobEngine.processAllBoxes()
        aliceEngine.inbox.process() // Handle Accept message

        val ctlEnvelope2 = CtlEnvelope(MakeMove(X, "b2"), "msgId", threadId)
        aliceEngine.handleMsg(aliceDID, bobDID, threadId, protoRef, ctlEnvelope2)
        eventually {
          aliceEngine.processAllBoxes()
          bobEngine.inbox.process() // Handle move message, send move
          bobEngine.processAllBoxes()
          aliceEngine.inbox.process() // Handle move message, send move
          complete shouldBe true
        }
      }
    }
  }

  def buildSendsMsgs(threadId: ThreadId): SendsMsgsExt = new SendsMsgsExt {
    def prepare(env: Envelope1[Any]): ProtocolOutgoingMsg = {
      engine.ProtocolOutgoingMsg(env.msg,
        env.to,
        env.frm,
        env.msgId.getOrElse(""),
        "",
        null,
        ThreadContextDetail(threadId))
    }

    override def send(pmsg: ProtocolOutgoingMsg): Unit = {
      outbox.add(pmsg)
    }

    override def sendSMS(toPhoneNumber: String, msg: String): Future[String] = ???
  }
}

class Inbox(engine: ProtocolEngineLite, val nickname: String) extends BoxLike[ProtocolOutgoingMsg, Any] {
  override def name: String = this.nickname + "ProtocolEngine.inbox"

  override def itemType: String = "ProtocolOutgoingMsg"

  override protected def processOneItem(pom: ProtocolOutgoingMsg): Any = {
    pom match {
      case pmfp: ProtocolOutgoingMsg
                          => engine.handleMsg(pmfp.to, pmfp.from, pmfp.threadContextDetail.threadId, protoRef, pmfp.envelope)
      case _              => throw new RuntimeException("not supported")
    }

  }
}

trait SendsMsgsExt extends SendsMsgs {
  var outbox: Inbox = _

  def setOutbox(outbox: Inbox): Unit = {
    this.outbox = outbox
  }
}

class TestProtocolEngine(name: String, sendsMsgs: SendsMsgs, cryptoFunctions: CryptoFunctions, logger: SimpleLoggerLike) extends ProtocolEngineLite(sendsMsgs, cryptoFunctions, logger) {
  val inbox = new Inbox(this, name)

  override def handleMsg(pinstId: PinstId, msg: Any): Any = {
    super.handleMsg(pinstId, msg)
    inbox.process()
  }

  override def handleMsg(myDID: DID, theirDID: DID, threadId: ThreadId, protoRef: ProtoRef, msg: Any): PinstId = {
    //TODO-rk: confirm few changes done here in fixing the merge conflicts
    val pinstId = super.handleMsg(myDID, theirDID, threadId, protoRef, msg)
    inbox.process()
    pinstId
  }
}
