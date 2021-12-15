package com.evernym.verity.protocol.protocols.basicMessage.v_1_0


import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Localization => l10n}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Role.Participator
import com.evernym.verity.did.didcomm.v1.decorators.{Base64, AttachmentDescriptor => Attachment}
import com.evernym.verity.protocol.engine.context.{ProtocolContextApi, Roster}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.legacy.BasicMessageLegacy

import java.util.UUID
import scala.util.{Failure, Success, Try}


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

class BasicMessage(val ctx: ProtocolContextApi[BasicMessage, Role, Msg, Event, State, String])
  extends Protocol[BasicMessage, Role, Msg, Event, State, String](BasicMessageDefinition)
    with BasicMessageLegacy {

  import BasicMessage._
  // Event Handlers

  override def applyEvent: ApplyEvent = mainEvent orElse legacyEvent

  def mainEvent: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized  )      => (State.Initialized(), initialize(e))
    case (_                      , _ , MyRole(n)       )      => (None, setRole(n))
    case (_: State.Initialized   , _ , e: MessageReceivedRef) => State.Messaging(buildMessage(e), Some(e.blobAddress))
    case (_: State.Messaging     , _ , e: MessageReceivedRef) => State.Messaging(buildMessage(e), Some(e.blobAddress))
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
    val sig: Signal.ReceivedMessage = Signal.ReceivedMessage(
      ctx.getRoster.selfId_!,
      m.`~l10n`,
      m.sent_time,
      m.content,
      m.`~attach`,
    )
    ctx.apply(MyRole(Participator.roleNum))
    saveMessage(m) {
      case Success(_) => ctx.signal(sig)
      case Failure(ex) =>
        ctx.logger.warn(s"could not store message: ${m.getClass.getSimpleName} because of ${ex.getMessage}")
        ctx.signal(sig)
    }
  }

  def send(m: Ctl.SendMessage): Unit = {

    ctx.apply(MyRole(Participator.roleNum))
    val messageMsg = Msg.Message(
      m.`~l10n`,
      m.sent_time,
      m.content,
      m.`~attach`,
    )
    saveMessage(messageMsg) {
      case Success(_) => ctx.send(messageMsg)
      case Failure(ex) => // TODO: handle error
    }
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

  def saveMessage(m: Msg.Message)(handler: Try[Unit] => Unit): Unit = {
    val segmentKey: SegmentKey = UUID.randomUUID().toString
    val messageData = MessageData(m.content, attachmentsToAttachmentObjects(m.`~attach`.getOrElse(Vector.empty)))
    ctx.storeSegment(segmentKey, messageData) { result =>
      handler(
        result.map { _ =>
          val ev = MessageReceivedRef(
            m.`~l10n`.locale,
            m.sent_time,
            segmentKey
          )
          ctx.apply(ev)
        }
      )
    }
  }

  // Not used yet, but example how data can be retrieved.
  def retrieveMessage(localization: Option[String], sentTime: String, blobAddress: String)(handler: Try[Msg.Message] => Unit): Unit = {
    val msg = Msg.Message(
      l10n(locale = localization),
      sentTime,
      "",
      None
    )

    ctx.withSegment[MessageData](blobAddress) {
      case Success(Some(data)) =>
        handler(
          Success(msg.copy(content = data.content, `~attach` = Some(attachmentObjectsToAttachments(data.attachments.toVector))))
        )
      case Success(None) => // no data in segmented state (probably expired)
        handler(Success(msg))
      case Failure(ex) => // failure in retrieving
        handler(Failure(ex))
    }
  }
}

object BasicMessage {
  def buildMessage(m: MessageReceivedRef): Msg.Message = {
    Msg.Message(
      l10n(locale = m.localization),
      m.sentTime,
      "",
      None,
    )
  }

  def attachmentsToAttachmentObjects(values: Vector[Attachment]): Vector[AttachmentObject] = {
    values.map(a => AttachmentObject(a.`@id`.getOrElse(""), a.`mime-type`.getOrElse(""), a.filename.getOrElse(""), a.data.base64))
  }
  def attachmentObjectsToAttachments(values: Vector[AttachmentObject]): Vector[Attachment] = {
    values.map(a => Attachment(Some(a.id), Some(a.mimeType), Base64(a.dataBase64), Some(a.filename)))
  }
}