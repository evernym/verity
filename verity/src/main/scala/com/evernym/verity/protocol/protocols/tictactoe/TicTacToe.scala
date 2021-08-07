package com.evernym.verity.protocol.protocols.tictactoe

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.tictactoe.Board.CellValue
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily._
/**
  * Roles used in this protocol
  */
//TODO this is not used very much. Either we don't need roles, or we need to add the abstraction they provide.
//See tictactoe-events.proto for roles


/**
  * Protocol Errors
  */
trait Error

/**
  * Exceptions signaled in this protocol
  */
class MissingRole extends RuntimeException("Role required for this operation")
class CellValueNotEmpty(cell: String) extends RuntimeException("cell not empty: " + cell)
class InvalidCell(cell: String) extends RuntimeException("invalid cell position: " + cell)
class NotYourTurn extends RuntimeException("it is not your turn")
class GameAlreadyFinished extends RuntimeException("game already finished")
class GameNotInitialized extends RuntimeException("game not initialized")
class CellSymbolAlreadyUsed(cellSymbol: CellValue)
  extends RuntimeException("cell symbol already used by another player: " + cellSymbol)

/** Protocols are defined in pairs, a protocol definition, and a protocol
  * instance. This is a protocol definition for the TicTacToe protocol.
  *
  */
object TicTacToeProtoDef extends ProtocolDefinition[TicTacToe, Role, Any, Any, State, String] {

  val msgFamily: MsgFamily = TicTacToeMsgFamily

  override def createInitMsg(params: Parameters) = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(PlayerA(), PlayerB())

  def initialState: State = State.Uninitialized() //TODO can this be generically implemented in the base class?
  override def create(context: ProtocolContextApi[TicTacToe, Role, Any, Any, State, String]): Protocol[TicTacToe, Role, Any, Any, State, String] = {
    new TicTacToe(context)
  }
}

/**
  * This embodies an instance of protocol for the game TicTacToe. This is
  * usually instantiated from a Protocol Definition object, in this case,
  * the object TicTacToeProtoDef.
  *
  */
class TicTacToe(val ctx: ProtocolContextApi[TicTacToe, Role, Any, Any, State, String])
  extends Protocol[TicTacToe, Role, Any, Any, State,String](TicTacToeProtoDef) {

  //TODO incoming or outgoing should not be dependent on this particular
  // protocol's state... this should be pulled up into a base class
  override def handleProtoMsg: (State, Option[Role], Any) ?=> Any = {
    case (_: State.Finished      , _                , _               ) => throw new GameAlreadyFinished
    case (_: State.Uninitialized , _                , _               ) => throw new GameNotInitialized
    case (_: State.Initialized   , None             , _: Offer        ) if ctx.getInFlight.sender.index.isDefined =>
      ctx.apply(Offered(ctx.getInFlight.sender.index_!))
      ctx.signal(AskAccept())
    case (_: State.Offered       , Some(_: PlayerB) , _: OfferAccept  ) => ctx.apply(Accepted()); ctx.signal(OfferAccepted(ctx.getInFlight.sender.index_!))
    case (_: State.Offered       , Some(_: PlayerB) , _: OfferDecline ) => ctx.apply(Declined()); ctx.signal(OfferDeclined(ctx.getInFlight.sender.index_!))
    case (_: State.Accepted      , _                , m: Move         ) => handleMoveMsg(m, GameState(), ctx.getInFlight.sender.index_!); ctx.signal(YourTurn(m.cellValue, m.at, ctx.getState))
    case (s: State.Playing       , _                , m: Move         ) => handleMoveMsg(m, s.game, ctx.getInFlight.sender.index_!); ctx.signal(YourTurn(m.cellValue, m.at, ctx.getState))
  }

  //TODO got to be a better way
  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  def mainHandleControl: (State, Control) ?=> Unit = {
    case (_: State.Uninitialized, Init(params)        ) => ctx.apply(Initialized(params.initParams.map(p => InitParam(p.name, p.value)).toSeq))
    case (_: State.Initialized  , _: MakeOffer        ) => ctx.apply(Offered(ctx.getRoster.selfIndex_!)); ctx.send(Offer()); ctx.signal(OfferSent())
    case (_: State.Offered      , _: AcceptOffer      ) => ctx.apply(Accepted()); ctx.send(OfferAccept()); ctx.signal(OfferAccepted(ctx.getBackState.roster.selfIndex_!))
    case (_: State.Offered      , _: DeclineOffer     ) => ctx.apply(Declined()); ctx.send(OfferDecline()); ctx.signal(OfferDeclined(ctx.getBackState.roster.selfIndex_!))
    case (_: State.Accepted     , c: MakeMove         ) => handleMakeMove(c, GameState(), ctx.getBackState.roster.selfIndex_!)
    case (s: State.Playing      , c: MakeMove         ) => handleMakeMove(c, s.game, ctx.getBackState.roster.selfIndex_!)
    case (_: State.Finished     , _                   ) => throw new GameAlreadyFinished
  }

  def getGameState: Option[GameState] = ctx.getState match {
    case State.Playing(game) => Some(game)
    case _ => None
  }

  def handleMakeMove(mmove: MakeMove, game: GameState, fromIdx: ParticipantIndex): Unit = {
    val move = mmove.generateMove
    handleMoveMsg(move, game, fromIdx)
    ctx.send(move)
  }

  def handleMoveMsg(move: Move, game: GameState, fromIdx: ParticipantIndex): Unit = {
    //TODO since we are using shadow state, I wonder if the application of the event catch a problem?
    game.validateMoveMsg(fromIdx, move)

    ctx.apply {
      Moved(move.cv, move.at, fromIdx)
    }

    checkForWinner()
    checkForDraw()
  }

  def checkForWinner(): Unit = {
    getGameState.flatMap(_.winner).foreach(w => {
      val board = ctx.getState.asInstanceOf[State.Playing].game.board
      ctx.apply(GameFinished("FINISHED", w))
      ctx.signal(DeclareWinner(w, board))
    })
  }

  def checkForDraw(): Unit = {
    getGameState.foreach(game => {
      if(game.board.isFull) {
        val board = ctx.getState.asInstanceOf[State.Playing].game.board
        ctx.apply(GameFinished("FINISHED", -1)) // TODO: Refactor tictactoe-events to support a draw
        ctx.signal(DeclareDraw(board))
      }
    })
  }

  /** Takes an state name, event, and current state, and returns a new state.
    *
    * @return a new state after the event is applied
    */
  def applyEvent: ApplyEvent = {
    //TODO is the state necessary here?
    case (_: State.Uninitialized    , _, Initialized(params)) => ( State.Initialized(), initialize(params) )
    case (_: State.Initialized      , _, Offered(offerer)   ) => ( State.Offered(), ctx.getRoster.withAssignment(
                                                                          PlayerA() -> offerer,
                                                                          PlayerB() -> ctx.getRoster.otherIndex(offerer)))
    case (_: State.Offered          , _, Declined()         ) => State.Declined()
    case (_: State.Offered          , _, Accepted()         ) => State.Accepted()
    case (_                         , _, Forfeited()        ) => State.Forfeited()
    case (_: State.Accepted         , _, e: Moved           ) => State.Playing(GameState().withMove(e))
    case (s: State.Playing          , _, e: Moved           ) => s.copy(game = s.game.withMove(e))
    case (_: State.Playing          , _, e: GameFinished    ) => State.Finished(GameResult(e.status, e.winnerIdx))
    case (state                     , _, event              ) => throw new UnhandledEvent(state, event)
  }


  //TODO: this still feels like boiler plate, need to come back and fix it
  def initialize(params: Seq[InitParam]): Roster[Role] = {
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

}


