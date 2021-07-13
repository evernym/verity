package com.evernym.verity.protocol.protocols.coinflip

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.util.DbcUtil.requireNotEmpty

import scala.util.Random

object CoinFlipMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "CoinFlip"
  override val version: MsgFamilyVersion = "0.1.0"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Init"        -> classOf[Init],
    "Start"       -> classOf[Start],
    "Continue"    -> classOf[Continue]
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "commit"  -> classOf[Commit],
    "reveal"  -> classOf[Reveal],
    "result"  -> classOf[Result]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[ShouldContinue]   -> "should-continue"
  )
}

object CoinFlipDefinition extends CoinFlipDefTrait

// separated CoinFlip Definition from the object (using a trait) to help with testing
trait CoinFlipDefTrait extends ProtocolDefinition[CoinFlip, Role, Msg, Event, CoinFlipState, String] {

  val msgFamily = CoinFlipMsgFamily

  override val roles: Set[Role] = Set(Caller(), Chooser())

  override def createInitMsg(params: Parameters): Control = Init(params)

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override def supportedMsgs: ProtoReceive = {
    case _: Msg =>
    case _: CoinFlipControl =>
  }

  def create(context: ProtocolContextApi[CoinFlip, Role, Msg, Event, CoinFlipState, String], mw: MetricsWriter):
    Protocol[CoinFlip, Role, Msg, Event, CoinFlipState, String] =
      new CoinFlip(context)

  def initialState = CoinFlipState.create()

}

sealed trait Role
case class Caller() extends Role
case class Chooser() extends Role

sealed trait Event
case class Initialized(myId: String, theirId: String) extends Event
case class MyRole(role: Role) extends Event
case class TheirRole(role: Role) extends Event
case class Value(role: Role, value: String) extends Event
case class Commitment(role: Role, commitment: String) extends Event
case class ValueCommitted(role: Role) extends Event
case class ValueRevealed(role: Role) extends Event
case class Erred(report: ProblemReport) extends Event
case class Won() extends Event
case class Lost() extends Event

sealed trait Msg extends MsgBase
case class Commit(commitment: String) extends Msg
case class Reveal(value: String) extends Msg
case class Result(winner: String) extends Msg
case class ProblemReport() extends Msg


/**
  * Decision Points
  */
case class ShouldContinue(send: String)


/**
  * Control messages
  */
trait CoinFlipControl extends Control
trait Decision extends Msg with CoinFlipControl
case class DoNotContinue(send: String) extends Decision {val answer = "no"}
case class Continue(send: String) extends Decision {val answer = "no"}

case class Init(params: Parameters) extends CoinFlipControl
case class Start() extends CoinFlipControl

/**
  * Errors and Exceptions
  */
class RevealDoesntMatchCommitment extends RuntimeException("reveal doesn't match commitment")


//noinspection RedundantBlock
class CoinFlip(val ctx: ProtocolContextApi[CoinFlip, Role, Msg, Event, CoinFlipState, String])
  extends Protocol[CoinFlip, Role, Msg, Event, CoinFlipState, String](CoinFlipDefinition)
    with CoinTools {

  val WINNER_ME  = "me"
  val WINNER_YOU = "you"

  override def applyEvent: ApplyEvent = {
    case (s, r, e: Initialized   ) =>
      (s.updateData(s.addIds(e.myId, e.theirId)), initialize(e.myId, e.theirId))
    case (s, r, e: MyRole        ) => s.updateData(s.addMyRole(e.role))
    case (s, r, e: TheirRole     ) => s.updateData(s.addTheirRole(e.role))
    case (s, r, e: Value         ) => s.updateData(s.addValue(e.role, e.value))
    case (s, r, e: Commitment    ) => s.updateData(s.addCommitment(e.role, e.commitment))
    case (s, r, _: ValueCommitted) => s.transitionTo[Committed]
    case (s, r, _: ValueRevealed ) => s.transitionTo[Revealed]
    case (s, r, _: Won           ) => s.transitionTo[Winner](s.addResult(s.data.myRole.get, isWinner = true))
    case (s, r, _: Lost          ) => s.transitionTo[Loser](s.addResult(s.data.myRole.get, isWinner = false))
  }

  override def handleProtoMsg: (CoinFlipState, Option[Role], Msg) ?=> Any = {
    case (_: New                    , _, m: Commit) => handleCommitWhileNew(m)
    case (_: Committed              , _, m: Commit) => handleCommitWhileCommitted(m)
    case (_: Committed              , _, m: Reveal) => handleRevealWhileCommitted(m)
    case (_: Revealed               , _, m: Reveal) => handleRevealWhileRevealed(m)
    case (_: Revealed               , _, m: Result) => handleWinner(m)
    case (_ @ (_: Winner | _: Loser), _, _: Result) =>
  }


  def handleControl: Control ?=> Any = {
    case m => handleControlWithState(m, ctx.getState)
  }


  def handleControlWithState: (Control, CoinFlipState) ?=> Unit = {
    case (Init(params), _: New) => handleInit(params)
    case (m: Start    , _: New) => handleStart(m)
    case (_: Continue , s: New) => {
      val myCommitment = commitToValue(s.getMyRoleData().value.get)
      ctx.apply(ValueCommitted(s.data.myRole.get))
      ctx.send(Commit(myCommitment))
    }

    case (_: Continue, s: Committed) => doReveal()

    case (_: Continue, s @ (_:Revealed| _:Winner |_:Loser)) => {
      val result = findWinner(ctx.getState)
      ctx.send(result)
    }
  }

  def handleInit(params: Parameters) = {
    val myId = params.paramValueRequired(SELF_ID)
    val theirId = params.paramValueRequired(OTHER_ID)
    ctx.apply(Initialized(myId, theirId))
  }

  def doReveal() = {
    ctx.apply(ValueRevealed(ctx.getState.data.myRole.get))
    ctx.send(Reveal(ctx.getState.getMyRoleData().value.get))
  }

  def findWinner(s: CoinFlipState): Msg = {
    // TODO check that all data is available

    if (s.getMyRoleData().value.get == s.getTheirRoleData().value.get) Result(WINNER_ME)
    else Result(WINNER_YOU)
  }

  def handleStart(msg: Start): Unit = {
    val myRole = Caller()
    val theirRole = Chooser()
    val myValue = chooseCoinValue()

    ctx.apply(MyRole(myRole))
    ctx.apply(TheirRole(theirRole))
    ctx.apply(Value(myRole, myValue))

    ctx.signal(ShouldContinue("Commit"))
  }

  def handleCommitWhileNew(msg: Commit): Unit = {
    if (ctx.getState.data.myRole.isDefined) {
      ??? //TODO error state
    }

    val myRole = Chooser()
    val theirRole = Caller()
    val myCommitment = chooseCoinValue()

    ctx.apply(MyRole(myRole))
    ctx.apply(TheirRole(theirRole))
    ctx.apply(Commitment(theirRole, msg.commitment))
    ctx.apply(Value(myRole, myCommitment))

    ctx.signal(ShouldContinue("Commit"))
  }
  def handleCommitWhileCommitted(msg: Commit): Unit = {
    ctx.apply(Commitment(ctx.getState.data.theirRole.get, msg.commitment))

    ctx.signal(ShouldContinue("Reveal"))
  }

  def handleRevealWhileCommitted(reveal: Reveal): Unit = {
    ctx.apply(Value(ctx.getState.data.theirRole.get, reveal.value))
    verifyReveal(reveal)

    ctx.signal(ShouldContinue("Reveal"))
  }

  def handleRevealWhileRevealed(reveal: Reveal): Unit = {
    ctx.apply(Value(ctx.getState.data.theirRole.get, reveal.value))
    verifyReveal(reveal)

    val result = findWinner(ctx.getState)
    result match {
      case m: Result => {
        m.winner match {
          case WINNER_ME => ctx.apply(Won())
          case WINNER_YOU => ctx.apply(Lost())
        }
      }
      case m: ProblemReport => {
        ctx.apply(Erred(m))
      }
      case m: Msg => {
        ??? //TODO what to do with invalid msg
      }
    }

    ctx.signal(ShouldContinue("Result"))
  }

  def verifyReveal(reveal: Reveal) = {
    val c = ctx.getState.getTheirRoleData().commitment.getOrElse(throw new RuntimeException("revealed before committed"))
    if (c != reveal.value) {
      throw new RevealDoesntMatchCommitment
    }
  }

  def handleWinner(msg: Result): Unit = {
    ctx.getState match {
      case _: Revealed => {
        msg.winner.trim.toLowerCase match {
          case WINNER_YOU => ctx.apply(Won())
          case WINNER_ME => ctx.apply(Lost())
        }
      }
      case _ => ??? //TODO error
    }
  }

  def initialize(myId: String, theirId: String): Roster[Role] = {
    ctx.updatedRoster(Seq(InitParamBase(SELF_ID, myId), InitParamBase(OTHER_ID, theirId)))
  }

}

trait CoinTools {
  val HEAD = "H"
  val TAILS = "T"

  val CoinSides = Array(HEAD, TAILS)

  def chooseCoinValue(): String = {
    requireNotEmpty(CoinSides, "CoinSides")
    CoinSides(Random.nextInt(CoinSides.length))
  }

  def commitToValue(value: String): String = value
}

