//package com.evernym.verity.protocol.protocols.tictactoe
//
//import com.evernym.verity.protocol.Control
//import com.evernym.verity.protocol.engine.Driver.SignalHandler
//import com.evernym.verity.protocol.engine.{Driver, Extension, ProtoDef, ProtocolEngine, SignalEnvelope}
//import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeTestExtension._
//
//case class ControllerGenParam(engine: ProtocolEngine[Any], ui: Any)
//
//object TicTacToeTestExtension {
//  type ControllerGen = ControllerGenParam => Driver
//  val controllerGen: ControllerGen = { cgParam =>
//    new Driver {
//      override def signal: SignalHandler = {
//        case SignalEnvelope(_: AskAccept, protoRef, pinstId, _) => None
//      }
//
//    }
//  }
//
//  def  uiGen(): ()=>Any = () => 42
//
//
//}
//
//class TicTacToeTestExtension(protoDef: ProtoDef) extends Extension(protoDef, controllerGen, uiGen )
//
