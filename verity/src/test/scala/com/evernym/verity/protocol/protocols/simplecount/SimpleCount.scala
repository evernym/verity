package com.evernym.verity.protocol.protocols.simplecount

import com.evernym.verity.protocol._
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.coinflip.TypeState

import scala.concurrent.ExecutionContext

object SimpleCountMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "SimpleCount"
  override val version: MsgFamilyVersion = "0.1.0"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map(
    "Start"     -> classOf[Start],
    "Increment" -> classOf[Increment]
  )
  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty //TODO
}

object SimpleCountDefinition extends ProtocolDefinition[SimpleCount, Role, Msg, Event, State, String] {

  val msgFamily = SimpleCountMsgFamily

  override val roles: Set[Role] = Set(Counter())

  override val initParamNames: Set[ParameterName] = Set()

  override def supportedMsgs: ProtoReceive = {
    case m: Msg =>
  }

  override def create(context: ProtocolContextApi[SimpleCount, Role, Msg, Event, State, String], executionContext: ExecutionContext):
  Protocol[SimpleCount, Role, Msg, Event, State, String] =
    new SimpleCount(context)

  def initialState: State = State.create()

}

sealed trait Role
case class Counter() extends Role

sealed trait Event
case class Started() extends Event
case class Incremented() extends Event

sealed trait Msg
case class Init(params: Parameters) extends Control
case class Start() extends Msg with Control
case class Increment()               extends Msg with Control

sealed trait State extends TypeState[StateData, State, Role] {
  def increment(): State = {
    updateData(data.copy(count = data.count + 1))
  }
}

case class Unstarted(data: StateData) extends State {
  override def transitions: Seq[Class[_]] = Seq(Counting.getClass)
}

case class Counting(data: StateData) extends State {
  override def transitions: Seq[Class[_]] = Seq()
}

object State {
  def create(): State = Unstarted(StateData())
}
case class StateData(count: Int=0)


//noinspection RedundantBlock
class SimpleCount(val ctx: ProtocolContextApi[SimpleCount, Role, Msg, Event, State, String])
  extends Protocol[SimpleCount, Role, Msg, Event, State, String](SimpleCountDefinition) {

  def applyEvent: ApplyEvent = {
    case (s, _, _: Started    ) => s.transitionTo[Counting]
    case (s, _, _: Incremented) => s.increment()
  }

  def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = ???

  def handleControl: Control ?=> Any = {
    case m => handleControlWithState(m, ctx.getState)
  }

  def handleControlWithState: (Control, State) ?=> Any = {
    case (_: Init, _)                =>
    case (_: Start, _: Unstarted)    => ctx.apply(Started())
    case (_: Increment, _: Counting) => ctx.apply(Incremented())
  }

}
