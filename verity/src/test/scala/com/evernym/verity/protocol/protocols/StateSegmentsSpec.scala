package com.evernym.verity.protocol.protocols

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.Bucket_2_Legacy
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.TestObjects._
import com.evernym.verity.protocol.testkit.TestsProtocolsImpl
import com.evernym.verity.testkit.BasicFixtureSpec
import org.scalatest.concurrent.Eventually

import scala.util.{Failure, Success}


class StateSegmentsSpec extends TestsProtocolsImpl(PhoneBookProtoDef, Option(Bucket_2_Legacy))
  with BasicFixtureSpec with Eventually {

  "A protocol" - {
    "should be able to specify that its state is segmented"  in { _ =>
      PhoneBookProtoDef.segmentedStateName shouldBe Option("phone-book")
    }

    "should be able to add a phone book entry " in { f =>
      val phoneBookService = f.bob
      val pbe = PhoneBookEntry("fn", "ln", "1111111")
      (f.alice engage phoneBookService) ~ Add(pbe)
      f.alice ~ Get("fn", "ln")

      f.alice.state shouldBe State.RetrievedEntry(Some(PhoneBookEntryAdded(pbe.fName, pbe.lName, pbe.phoneNumber)))
      f.alice.role shouldBe User
      phoneBookService.role shouldBe PhoneBookService
    }

    "should store data remotely if size is bigger than journal max" in { f =>
      val phoneBookService = f.bob
      val pbe = PhoneBookEntry("fn", "ln", Array.range(0, 700000).toString)
      (f.alice engage phoneBookService) ~ Add(pbe)
      f.alice ~ Get("fn", "ln")

      f.alice.state shouldBe State.RetrievedEntry(Some(PhoneBookEntryAdded(pbe.fName, pbe.lName, pbe.phoneNumber)))
      f.alice.role shouldBe User
      phoneBookService.role shouldBe PhoneBookService

    }
  }
}

object TestObjects {

  // Roles
  sealed trait Role
  object User extends Role
  object PhoneBookService extends Role

  // States
  trait State
  object State {
    case class Uninitialized() extends State
    case class Initialized() extends State
    case class RoleSet() extends State
    case class RetrievedEntry(pbe: Option[PhoneBookEntryAdded]) extends State
  }

  trait PhoneBookSegmentState
  case class PhoneBookEntry(fName: String, lName: String, phoneNumber: String) extends PhoneBookSegmentState {
    def key: String = s"$fName-$lName"
  }


  // Main Messages
  trait PhoneBookProtoMsg extends MsgBase
  trait PhoneBookEntryBase {
    def fName: String
    def lName: String

    def key = s"$fName-$lName"
  }
  case class AddPhoneBookEntry(entry: PhoneBookEntry) extends PhoneBookProtoMsg
  case class GetPhoneBookEntry(fName: String, lName: String) extends PhoneBookProtoMsg with PhoneBookEntryBase
  case class PhoneBookLookupResult(entry: Option[PhoneBookEntryAdded]) extends PhoneBookProtoMsg

  // Control Messages
  trait ControlMsg extends Control with MsgBase
  case class Init(params: Parameters) extends ControlMsg
  case class Add(entry: PhoneBookEntry) extends ControlMsg
  case class Get(fName: String, lName: String) extends ControlMsg
  case class GetResult(entry: Option[PhoneBookEntry]) extends ControlMsg

  // Events
  trait PhoneBookEvt
  case class InitParamEvt(name: String, value: String) extends PhoneBookEvt
  case class Initialized(seq: Seq[InitParamEvt]) extends PhoneBookEvt
  case class Added(idx: ParticipantIndex) extends PhoneBookEvt
  case class RetrievedEntry(entry: Option[PhoneBookEntryAdded]) extends PhoneBookEvt
  case class PhoneBookEntryAdded(fName: String, lName: String, phoneNumber: String) extends PhoneBookEvt with PhoneBookEntryBase

  object PhoneBookMsgFamily extends MsgFamily {
    override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
    override val name: MsgFamilyName = "PhoneBook"
    override val version: MsgFamilyVersion = "0.1"
    override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
      "add-phone-book-entry" -> classOf[AddPhoneBookEntry],
      "get-phone-book-entry" -> classOf[GetPhoneBookEntry],
      "phone-book-lookup-result" -> classOf[PhoneBookLookupResult]
    )

    override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
      "init"        -> classOf[Init],
      "add"         -> classOf[Add],
      "get"         -> classOf[Get],
      "get-result"  -> classOf[GetResult]
    )
  }

  // ProtoDef
  object PhoneBookProtoDef extends ProtocolDefinition[PhoneBookProto, Role, PhoneBookProtoMsg, PhoneBookEvt, State, String] {

    val msgFamily: MsgFamily = PhoneBookMsgFamily

    override val segmentedStateName = Option("phone-book")

    override def initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

    override def createInitMsg(params: Parameters) = Init(params)

    override def supportedMsgs: ProtoReceive = {
      case _: Control =>
      case _: PhoneBookProtoMsg =>
    }

    def create(ctx: ProtocolContextApi[PhoneBookProto, Role, PhoneBookProtoMsg, PhoneBookEvt, State, String]): PhoneBookProto = {
      new PhoneBookProto(ctx)
    }

    def initialState: State = State.Uninitialized()

  }


  // Protocol
  class PhoneBookProto(val ctx: ProtocolContextApi[PhoneBookProto, Role, PhoneBookProtoMsg, PhoneBookEvt, State, String])
    extends Protocol[PhoneBookProto, Role, PhoneBookProtoMsg, PhoneBookEvt, State, String](PhoneBookProtoDef) {

    def handleProtoMsg: (State, Option[Role], PhoneBookProtoMsg) ?=> Any = {

      case (_, None, apbe: AddPhoneBookEntry) =>
        ctx.apply(Added(ctx.getInFlight.sender.index_!))
        addPhoneBookEntry(apbe)

      case (_, Some(User), apbe: AddPhoneBookEntry) =>
        addPhoneBookEntry(apbe)

      case (_, Some(User), gpbe: GetPhoneBookEntry) =>
        ctx.withSegment[PhoneBookEntryAdded](gpbe.key) {
          case Success(entry: Option[PhoneBookEntryAdded]) => ctx.send(PhoneBookLookupResult(entry))
          case Failure(exception)   => throw exception
        }

      case (_, Some(PhoneBookService), result: PhoneBookLookupResult) =>
        ctx.apply(RetrievedEntry(result.entry))
    }

    def addPhoneBookEntry(apbe: AddPhoneBookEntry): Unit = {
      ctx.storeSegment(apbe.entry.key, PhoneBookEntryAdded(apbe.entry.fName, apbe.entry.lName, apbe.entry.phoneNumber))
    }

    def handleControl: Control ?=> Any = {
      case c: Control => mainHandleControl(ctx.getState, c)
    }

    def mainHandleControl: (State, Control) ?=> Unit = {
      case (_, Init(params)) => ctx.apply(Initialized(params.initParams.map(p => InitParamEvt(p.name, p.value)).toSeq))

      case (_:State.Initialized, add: Add)     =>
        ctx.apply(Added(ctx.getRoster.selfIndex_!))
        ctx.send(AddPhoneBookEntry(add.entry))

      case (_:State.RoleSet, add: Add)     =>
        ctx.send(AddPhoneBookEntry(add.entry))

      case (_, get: Get)    => ctx.send(GetPhoneBookEntry(get.fName, get.lName))
    }

    def applyEvent: ApplyEvent = {
      case (_ , _, Initialized(params)   ) => ( State.Initialized(), initialize(params) )
      case (_ , _, Added(userIdx)        ) => ( State.RoleSet(), setRoles(userIdx))
      case (_ , _, RetrievedEntry(entry) ) => State.RetrievedEntry(entry)
    }

    def setRoles(user: ParticipantIndex): Roster[Role] = ctx.getRoster.withAssignment(
      User -> user,
      PhoneBookService -> ctx.getRoster.otherIndex(user)
    )

    def initialize(params: Seq[InitParamEvt]): Roster[Role] = {
      ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
    }
  }

}
