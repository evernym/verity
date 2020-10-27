package com.evernym.verity.protocol.protocols.basicMessage.v_1_0

import java.util.UUID

import com.evernym.verity.Base64Encoded
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Localization => l10n, Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.Participator
import com.evernym.verity.protocol.didcomm.decorators.{Base64, AttachmentDescriptor => Attachment}
import com.evernym.verity.util.Base64Util.{getBase64Decoded, getBase64Encoded}
import com.evernym.verity.util.TimeUtil._

sealed trait Role
object Role {
  case object Participator extends Role {
    def roleNum = 0
  }
  case object Receiver extends Role {
    def roleNum = 1
  }
  def numToRole: Int ?=> Role = {
    case 0 => Participator
  }
  def otherRole: Role ?=> Role = {
    case Participator => Participator
  }
}
trait Event
class BasicMessage(val ctx: ProtocolContextApi[BasicMessage, Role, Msg, Event, State, String])
  extends Protocol[BasicMessage, Role, Msg, Event, State, String](BasicMessageDefinition) {
  import BasicMessage._
  // Event Handlers

  def applyEvent: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized  ) => (State.Initialized(), initialize(e))
    case (_                      , _ , MyRole(n)       ) => (None, setRole(n))
    case (_: State.Initialized   , _ , e: MessageSent  ) => State.Messaging(buildMessage(e))
    case (_: State.Messaging     , _ , e: MessageSent  ) => State.Messaging(buildMessage(e))
  }
  // Protocol Msg Handlers
  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized  , _ , m: Msg.Message ) => receiveMessage(m)
    case (_: State.Messaging  , _ , m: Msg.Message ) => receiveMessage(m)
  }
  // Control Message Handlers
  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }
  def mainHandleControl: (State, Control) ?=> Unit = {
    case (State.Uninitialized(),     m: Ctl.Init)            => ctx.apply(Initialized(m.selfId, m.otherId))
    case (_: State.Initialized,      m: Ctl.SendMessage)     => send(m)
    case (_: State.Messaging,        m: Ctl.SendMessage)     => send(m)
  }

  def receiveMessage(m: Msg.Message): Unit = {
    ctx.apply(MyRole(Participator.roleNum))
    ctx.apply(messageToEvt(m))

    val signal = Signal.ReceivedMessage(
      m.`~l10n`,
      m.sent_time,
      m.content,
      m.`~attach`,
    )
    ctx.signal(signal)
  }

  def send(m: Ctl.SendMessage): Unit = {
    ctx.apply(MyRole(Participator.roleNum))
    val messageMsg = Msg.Message(
      m.`~l10n`,
      m.sent_time,
      m.content,
      m.`~attach`,
    )
    ctx.apply(messageToEvt(messageMsg))
    ctx.send(messageMsg, Some(Participator), Some(Participator))
  }

  // Helper Functions
  def setRole(role: Int): Option[Roster[Role]] = {
    val myRole = Role.numToRole(role)
    val otherAssignment = Role.otherRole(myRole) -> ctx.getRoster.otherId()
    ctx.getRoster.withSelfAssignment(myRole).withAssignmentById(otherAssignment)
  }

  def initialize(p: Initialized): Roster[Role] = {
    ctx.updatedRoster(Seq(InitParamBase(SELF_ID, p.selfIdValue), InitParamBase(OTHER_ID, p.otherIdValue)))
  }
}

object BasicMessage {
  def buildMessage(m: MessageSent): Msg.Message = {
    Msg.Message(
      l10n(locale = Some(m.localization)),
      BaseTiming(out_time = Some(m.sentTime)),
      m.content,
      Some(attachmentObjectsToAttachments(m.attachments.toVector)),
    )
  }

  def messageToEvt(m: Msg.Message): MessageSent = {
    if(m.`~attach`.isEmpty){
      MessageSent(
        m.`~l10n`.locale.get,
        m.sent_time.out_time.get,
        m.content,
      )
    }
    else {
      MessageSent(
        m.`~l10n`.locale.get,
        m.sent_time.out_time.get,
        m.content,
        attachmentsToAttachmentObjects(m.`~attach`.get),
      )
    }
  }

  def attachmentsToAttachmentObjects(values: Vector[Attachment]): Vector[AttachmentObject] = {
    values.map(a => AttachmentObject(a.`@id`.getOrElse(""), a.`mime-type`.getOrElse(""), a.filename.getOrElse(""), a.data.base64))
  }
  def attachmentObjectsToAttachments(values: Vector[AttachmentObject]): Vector[Attachment] = {
    values.map(a => Attachment(Some(a.id), Some(a.mimeType), Base64(a.dataBase64), Some(a.filename)))
  }
}