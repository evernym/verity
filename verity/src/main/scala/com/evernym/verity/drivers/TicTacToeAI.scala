package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily._
import com.evernym.verity.protocol.protocols.tictactoe._

import scala.util.Random

object TicTacToeAI {

  // FIXME: Implement a little bit of logic. Minmax algorithm? Random for now.
  def chooseCell(board: Board): String = {
    getRandomCell(board.emptyCells)
  }

  def getRandomCell(possibleCells: Seq[String]): String = {
    val random = new Random()
    possibleCells(random.nextInt(possibleCells.length))
  }
}

class TicTacToeAI(cp: ActorDriverGenParam) extends ActorDriver(cp) {
  var board: Board = new Board()

  /**
    * Takes a SignalEnvelope and returns an optional Control message.
    *
    * A control message can always be sent later, in which case returning
    * a None is appropriate.
    *
    * @return an optional Control message
    */
  override def signal[A]: SignalHandler[A] = {
    case SignalEnvelope(_: AskAccept, _, _, _, _) => Option(AcceptOffer())
    case SignalEnvelope(m: YourTurn, _, _ , _, _) =>
      board = board.updateBoard(m.value, m.atPosition)
      if(m.state.isInstanceOf[State.Finished]) {
        None
      } else {
        val cellValue = Board.getOtherCellValue(m.value)
        val cell = TicTacToeAI.chooseCell(board)
        board = board.updateBoard(cellValue, cell)
        Option(MakeMove(cellValue, cell))
      }
    case SignalEnvelope(_: DeclareWinner, _, _, _, _) => None
    case SignalEnvelope(_: DeclareDraw, _, _, _, _) => None
    case SignalEnvelope(_, _, _, _, _) => None // Generic catch all for now since other signals we don't need are being sent.
  }
}
