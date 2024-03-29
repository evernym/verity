package com.evernym.verity.protocol.protocols

import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{EVERNYM_QUALIFIER, MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry.DriverGen
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy.OneToOne
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.TestObjects2._
import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.{InteractionController, SimpleControllerProviderInputType, TestSimpleProtocolSystem, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec
import com.evernym.verity.util.Conversions._
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.concurrent.Eventually

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


class StateSegments2Spec extends TestsProtocolsImpl(PhoneBookProtoDef, Option(OneToOne))
  with BasicFixtureSpec with Eventually {

  "A protocol" - {
    "should be able to specify that its state is segmented"  in { _ =>
      PhoneBookProtoDef.segmentStoreStrategy shouldBe Some(OneToOne)
    }
    "should be able to add a phone book entry " in { f =>

      implicit val system = new TestSimpleProtocolSystem()

      val controllerProvider: DriverGen[SimpleControllerProviderInputType] =
        Option{{ (i: SimpleControllerProviderInputType, e: ExecutionContext) =>
            new InteractionController(i) {
              override def signal[A]: SignalHandler[A] = {
                case SignalEnvelope(pber: PhoneBookEntryReceived, _, _, _, _) =>
                  None
              }
            }
          }
        }

      val phoneBookService = setup("phone-book-service", controllerProvider, it=OneParty)

      val pbe = PhoneBookEntry("fn", "ln", "1111111")
      phoneBookService ~ Add(pbe)
      phoneBookService ~ Get("fn", "ln")

    }
  }
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}

object TestObjects2 {

  // Roles
  sealed trait Role
  object User extends Role
  object PhoneBookService extends Role

  // States
  trait State
  object State {
    case class Uninitialized() extends State
    case class Initialized() extends State
  }

  trait PhoneBookEntryBase {
    def fName: String
    def lName: String

    def key = s"$fName-$lName"
  }

  trait PhoneBookSegmentState
  case class PhoneBookEntry(fName: String, lName: String, phoneNumber: String) extends PhoneBookSegmentState with PhoneBookEntryBase


  trait PhoneBookProtoMsg

  // Control Messages
  trait ControlMsg extends Control with MsgBase
  case class Init(params: Parameters) extends ControlMsg
  case class Add(entry: PhoneBookEntry) extends ControlMsg
  case class Get(fName: String, lName: String) extends ControlMsg with PhoneBookEntryBase

  // Events
  trait PhoneBookEvt
  case class InitParamEvt(name: String, value: String) extends PhoneBookEvt
  case class Initialized(seq: Seq[InitParamEvt]) extends PhoneBookEvt
  case class PhoneBookEntryAdded(fName: String, lName: String, phoneNumber: String) extends PhoneBookEvt with PhoneBookEntryBase

  //Signal
  case class PhoneBookEntryReceived(pbe: PhoneBookEntry)


  object PhoneBookMsgFamily extends MsgFamily {
    override val qualifier: MsgFamilyQualifier = EVERNYM_QUALIFIER
    override val name: MsgFamilyName = "PhoneBook"
    override val version: MsgFamilyVersion = "0.2"
    override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty

    override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
      "init"        -> classOf[Init],
      "add"         -> classOf[Add],
      "get"         -> classOf[Get],
    )

    override protected val signalMsgs: Map[Class[_], MsgName] = Map(
      classOf[PhoneBookEntryReceived] -> "phone-book-entry-received"
    )
  }
  // ProtoDef
  object PhoneBookProtoDef extends ProtocolDefinition[PhoneBookProto, Role, PhoneBookProtoMsg, PhoneBookEvt, State, String] {

    val msgFamily: MsgFamily = PhoneBookMsgFamily

    override def segmentStoreStrategy: Option[SegmentStoreStrategy] = Some(OneToOne)

    override def initParamNames: Set[ParameterName] = Set(SELF_ID)

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

    def handleProtoMsg: (State, Option[Role], PhoneBookProtoMsg) ?=> Any = PartialFunction.empty

    def handleControl: Control ?=> Any = {
      case c: Control => mainHandleControl(ctx.getState, c)
    }

    def mainHandleControl: (State, Control) ?=> Unit = {
      case (_, Init(params)) =>
        ctx.apply(Initialized(params.initParams.map(p => InitParamEvt(p.name, p.value)).toSeq))

      case (_:State.Initialized, add: Add)     =>
        ctx.storeSegment(add.entry.key, PhoneBookEntryAdded(add.entry.fName, add.entry.lName, add.entry.phoneNumber)) {
          case Success(_: StoredSegment) =>
          case Failure(e) => throw e
        }

      case (_, g: Get)    =>
        ctx.withSegment[PhoneBookEntryAdded](g.key) {
          case Success(Some(value)) =>
            ctx.signal(PhoneBookEntryReceived(PhoneBookEntry(value.fName, value.lName, value.phoneNumber)))
          case Success(None)        => //nothing to do
          case Failure(exception)   => throw exception
        }
    }

    def applyEvent: ApplyEvent = {
      case (_ , _, Initialized(params)   ) => ( State.Initialized(), initialize(params) )
    }

    def initialize(params: Seq[InitParamEvt]): Roster[Role] = {
      ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
    }
  }

}
