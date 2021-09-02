package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{MsgBase, Parameters, ParticipantIndex}
import com.evernym.verity.protocol.protocols.tictactoe.Board.{CellValue, O, X, empty}

object TicTacToeMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  val name = "TicTacToe"
  val version: MsgFamilyVersion = "0.5"

  case class Offer() extends MsgBase
  case class OfferAccept() extends MsgBase
  case class OfferDecline() extends MsgBase

  object Move {

    def cellValueMapReverseMap[CellValue, String] = cellValueMap map (_.swap)

    def cellValueMap: Map[String, CellValue] = Map(
      "X" -> X,
      "O" -> O,
      "" -> empty
    )

    def apply(cv: CellValue, at: String): Move = {
      Move(cellValueMapReverseMap(cv), at)
    }
  }

  case class Move(cv: String, at: String) extends MsgBase {
    def cellValue: CellValue = Move.cellValueMap(cv)
  }

  protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] =  Map (
    "OFFER"         -> classOf[Offer],
    "OFFER_ACCEPT"  -> classOf[OfferAccept],
    "OFFER_DECLINE" -> classOf[OfferDecline],
    "MOVE"          -> classOf[Move]
  )

  sealed trait TicTacToeControl extends Control with MsgBase
  case class Init(params: Parameters) extends TicTacToeControl
  case class MakeOffer() extends TicTacToeControl
  case class AcceptOffer() extends TicTacToeControl
  case class DeclineOffer() extends TicTacToeControl
  case class MakeMove(cv: CellValue, at: String) extends TicTacToeControl {
    def generateMove = Move(cv, at)
  }

  override val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] =  Map (
    "Init"                  -> classOf[Init],
    "MAKE_OFFER"            -> classOf[MakeOffer],
    "ACCEPT_OFFER"          -> classOf[AcceptOffer],
    "DECLINE_OFFER"         -> classOf[DeclineOffer],
    "MAKE_MOVE"             -> classOf[MakeMove]
  )


  /**
    * Signal Messages
    */
  sealed trait SignalMsg
  case class AskAccept() extends SignalMsg
  case class YourTurn(value: CellValue, atPosition: String, state: State) extends SignalMsg
  case class DeclareWinner(winner: ParticipantIndex, board: Board) extends SignalMsg
  case class DeclareDraw(board: Board) extends SignalMsg
  case class OfferSent() extends SignalMsg
  case class OfferAccepted(participant: ParticipantIndex) extends SignalMsg
  case class OfferDeclined(participant: ParticipantIndex) extends SignalMsg

  override val signalMsgs: Map[Class[_], MsgName] = Map (
    classOf[AskAccept]      -> "ASK_ACCEPT",
    classOf[YourTurn]       -> "YOUR_TURN",
    classOf[DeclareWinner]  -> "DECLARE_WINNER",
    classOf[DeclareDraw]    -> "DECLARE_DRAW",
    classOf[OfferSent]      -> "OFFER_SENT",
    classOf[OfferAccepted]  -> "OFFER_ACCEPTED",
    classOf[OfferDecline]   -> "OFFER_DECLINED"
  )

}
