//package com.evernym.verity.protocol.engine
//
//import java.io.{ByteArrayOutputStream, StringReader}
//
//import com.evernym.verity.protocol.Control
//import com.evernym.verity.protocol.engine.Driver.SignalHandler
//import com.evernym.verity.protocol.protocols.tictactoe.Msg.Offer
//import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeControl.AcceptOffer
//import com.evernym.verity.protocol.protocols.tictactoe.{AskAccept, TicTacToeProtoDef}
//import com.evernym.verity.testkit.BasicSpec

//
//import scala.concurrent.Future
//import com.evernym.verity.CustomExecutionContext.executionContext
//import scala.io.StdIn
//
//class ProtocolEngineSpec extends BasicSpec {
//  "A protocol engine" - {
//    "can dynamically register an extension" in {
//      val engine = new ProtocolEngine[CmdlineUI]
//
//      class CmdlineUI {
//        def ask(prompt: String): Future[String] = Future { StdIn.readLine() }
//        def tell(message: String): Unit = ???
//      }
//
//      def uiGen(): engine.UiGen = () => new CmdlineUI
//
//      val controllerGen: engine.ControllerGen = { cgParam =>
//        new Driver {
//          override def signal: SignalHandler = {
//            case SignalEnvelope(_: AskAccept, protoRef, pinstId, _) =>
//              val respFuture = cgParam.ui.ask(s"Someone wants to play $protoRef with you. Do you want to play?")
//              respFuture map { response =>
//                if (response.trim.toLowerCase == "yes")
//                  cgParam.engine.handleMsg(pinstId, AcceptOffer())
//              }
//              None
//          }
//
//        }
//      }
//
//      val ticTacToeExtension: engine.ExtensionType = new Extension(TicTacToeProtoDef, controllerGen, uiGen)
//
//      engine.registerExtension(ticTacToeExtension)
//
//      val safeThreadId = "1"
//      val protoRef = TicTacToeProtoDef.protoRef
//
//      val in = new StringReader("yes")
//      val out = new ByteArrayOutputStream()
//      Console.withOut(out) {
//        Console.withIn(in) {
//          engine.handleMsg(safeThreadId, protoRef, Offer)
//        }
//      }
//
//      Thread.sleep(100)
//    }
//  }
//}
