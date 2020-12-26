package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.external_api_access.AccessRight
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateProtoDef
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.{Control, SystemMsg}
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.HashUtil.byteArray2RichBytes

import scala.language.implicitConversions
import scala.util.matching.Regex

object Scope {
  sealed trait ProtocolScope

  case object Agent extends ProtocolScope
  case object Relationship extends ProtocolScope
  case object Adhoc extends ProtocolScope

  case object RelProvisioning extends ProtocolScope
}

/**
  *
  * @tparam P Protocol type
  * @tparam R Role type
  * @tparam M Message type
  * @tparam E Event type
  * @tparam S State type
  * @tparam I Message Recipient Identifier Type
  */
trait ProtocolDefinition[P,R,M,E,S,I] extends SegmentedStateProtoDef[S] {

  import Scope._

  val msgFamily: MsgFamily

  //TODO: once all protocol defs have implemented `inputs`, we may remove `supportedMsgs`
  // until then though, all protocol definition will have to provide implementation of this method
  def supportedMsgs: ProtoReceive = Map.empty

  def scope: ProtocolScope = {
    // TODO log warning that protocol has not defined its scope
    Scope.Adhoc
  }

  val roles: Set[R] = Set.empty

  val requiredAccess: Set[AccessRight] = Set.empty

  def create(context: ProtocolContextApi[P,R,M,E,S,I]): Protocol[P,R,M,E,S,I]

  def initialState: S

  def initParamNames: Set[ParameterName] = Set.empty

  def createInitMsg(params: Parameters): Any = {
    if (initParamNames.isEmpty) {
      throw new RuntimeException("createInitMsg was called with no initParamNames defined")
    } else {
      throw new RuntimeException("initParamNames are defined, yet createInitMsg was not overridden; createInitMsg must be defined if initParamNames is")
    }
  }

  def protocolIdSuffix[A](typedMsg: TypedMsgLike[A]): Option[String] = None

}

/**
  *
  * @tparam P Protocol type
  * @tparam R Role type
  * @tparam M Message type
  * @tparam E Event type
  * @tparam S State type
  * @tparam I Message Recipient Identifier Type
  */
abstract class Protocol[P,R,M,E,S,I]
  (val definition: ProtocolDefinition[P,R,M,E,S,I]) {

  def ctx: ProtocolContextApi[P,R,M,E,S,I]

  // TODO Document these functions, for example what role is the Option[R]. What does it semantically mean
  def handleProtoMsg: (S, Option[R], M) ?=> Any

  def handleControl: Control ?=> Any

  def applyEvent: ApplyEvent

  def handleSystemMsg: SystemMsg ?=> Any = {
    case m => ctx.logger.info(s"System Msg handler for protocol is unimplemented, msg: $m was not received")
  }

  /**
    * StateTuple is used to indicate a change in either the state, the roster,
    * or both. The implicit conversions below make it so one can return just
    * what has changed, simplifying the code.
    */
  type EventInfo = (S, Roster[R], E)
  type ApplyEvent = EventInfo ?=> StateChangeTuple
  type StateChangeTuple = (Option[S], Option[Roster[R]])
  type StateTuple = (S, Roster[R])

  implicit def stateChangeTuple2StateTuple(sct: StateChangeTuple): StateTuple = {
    (sct._1.getOrElse(ctx.getState), sct._2.getOrElse(ctx.getRoster))
  }

  implicit def state2OptionState(newState: S): Option[S] = Option(newState)
  implicit def roster2OptionRoster(r: Roster[R]): Option[Roster[R]] = Option(r)
  implicit def state2StateTuple(newState: S): StateChangeTuple = (Option(newState), None)
  implicit def tuple2StateTuple(t: StateTuple): StateChangeTuple = (Option(t._1), Option(t._2))
  implicit def roster2StateTuple(newRoster: Roster[R]): StateChangeTuple = (None, Option(newRoster))

}

case class Participant(role: String, did: DID)

object Parameters {
  /** Alternate constructor that can be more concise in some circumstances
    *
    * @example Parameters("name" -> "Carla", "age" -> "20")
    *
    * @param pairs variable number of tuples
    * @return an instance of Parameters
    */
  def apply(pairs: (ParameterName, ParameterValue)*): Parameters = {
    Parameters(pairs.map(p => Parameter(p._1, p._2)).toSet)
  }
}

case class Parameters(initParams: Set[Parameter]=Set.empty) {

  def paramValue(name: ParameterName): Option[ParameterValue] =
    initParams.find(_.name == name).map(_.value)

  def paramValueRequired(name: ParameterName): ParameterValue =
    paramValue(name).getOrElse {
      val errorMsg = s"init param with name '$name' not found"
      throw new ProtocolEngineException(errorMsg)
    }

}

trait ProtoSystemEvent

trait ProtoContainerEvent extends ProtoSystemEvent

case class MultiEvent(evts: Seq[Any]) extends ProtoSystemEvent

case class InitParamBase(name: String, value: String)

object ProtoRef {
  val VALID_PROTO_REF_REG_EX: Regex = "(.*)\\[(.*)\\]".r

  def fromString(str: String): ProtoRef =
    str match {
      case VALID_PROTO_REF_REG_EX(msgFamilyName, msgFamilyVersion) => ProtoRef(msgFamilyName, msgFamilyVersion)
      case _ => throw new RuntimeException("invalid proto ref string: " + str)
    }
}

case class ProtoRef(msgFamilyName: MsgFamilyName, msgFamilyVersion: MsgFamilyVersion) {
  override def toString: String = s"$msgFamilyName[$msgFamilyVersion]"
  def toHash: String = HashUtil.safeMultiHash(SHA256, msgFamilyName, msgFamilyVersion).hex
}

class UnhandledControlMsg(val state: Any, val control: Any)
  extends RuntimeException(s"no handler for control message $control in state $state")

class UnhandledEvent(val state: Any, val event: Any)
  extends RuntimeException(s"no handler for event $event with state $state") {
}
