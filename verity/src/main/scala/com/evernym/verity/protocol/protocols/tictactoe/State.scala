package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.protocol.engine.context.Roster
import com.evernym.verity.protocol.engine.{ParticipantId, ParticipantIndex}
import com.evernym.verity.protocol.protocols.tictactoe.Board.CellValue
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.Move

sealed trait State
object State {
  case class Uninitialized() extends State
  case class Initialized() extends State
  case class Offered() extends State
  case class Declined() extends State
  case class Accepted() extends State
  case class Playing(game: GameState) extends State with HasGame
  case class Forfeited() extends State
  case class Finished(result: GameResult) extends State
}

trait HasGame {
  def game: GameState
  def lastMover[R](roster: Roster[R]): Option[ParticipantId] = game.lastMoverIdx.map(roster.participants)
}

case class GameState(symbolAssignments: Map[CellValue, ParticipantIndex] = Map(),
                     lastMoverIdx: Option[ParticipantIndex] = None,
                     board: Board = Board()) {

  def validateMoveMsg(from: ParticipantIndex, p: Move): Unit = {
    if (lastMoverIdx.contains(from)) {
      throw new NotYourTurn
    }

    if (! board.isEmptyCell(p.at)) {
      throw new CellValueNotEmpty(p.at)
    }

    if (! symbolAssignments.get(p.cellValue).forall(_ == from)) {
      throw new CellSymbolAlreadyUsed(p.cellValue)
    }
  }

  def withMove(placed: Moved): GameState = {
    val cv = Board.cellValueFromStr(placed.value)
    val newSymbolAssignments = if (!symbolAssignments.contains(cv)) {
      symbolAssignments + (cv -> placed.byPlayerIdx)
    } else symbolAssignments
    copy(
      board = board.updateBoard(Board.cellValueFromStr(placed.value), placed.atPosition),
      lastMoverIdx = Option(placed.byPlayerIdx),
      symbolAssignments = newSymbolAssignments
    )
  }

  def winner: Option[ParticipantIndex] = board.getWinner.map(symbolAssignments)

}

case class GameResult(status: String, winner: ParticipantIndex)




