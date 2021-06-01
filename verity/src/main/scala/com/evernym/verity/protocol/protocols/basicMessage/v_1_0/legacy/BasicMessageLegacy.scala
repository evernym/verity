package com.evernym.verity.protocol.protocols.basicMessage.v_1_0.legacy

import com.evernym.verity.protocol.engine.{Protocol, ProtocolContextApi}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessage.attachmentObjectsToAttachments
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.{BasicMessage, Event, Msg, Role, State}
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Localization => l10n}
import com.evernym.verity.protocol.protocols.ProtocolHelpers


trait BasicMessageLegacy
  extends Protocol[BasicMessage, Role, Msg, Event, State, String]
    with ProtocolHelpers[BasicMessage, Role, Msg, Event, State, String] {

  override type Context = ProtocolContextApi[BasicMessage, Role, Msg, Event, State, String]
  implicit val ctx: Context

  def legacyEvent: ApplyEvent = {
    case (_: State.Initialized   , _ , e: MessageReceived  ) => State.Messaging(buildLegacyMessage(e))
    case (_: State.Messaging     , _ , e: MessageReceived  ) => State.Messaging(buildLegacyMessage(e))
  }

  def buildLegacyMessage(m: MessageReceived): Msg.Message = {
    Msg.Message(
      l10n(locale = m.localization),
      m.sentTime,
      m.content,
      Some(attachmentObjectsToAttachments(m.attachments.toVector)),
    )
  }
}
