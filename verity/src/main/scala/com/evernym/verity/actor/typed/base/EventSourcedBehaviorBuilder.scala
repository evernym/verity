package com.evernym.verity.actor.typed.base

import akka.actor.typed.Signal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}

//used for persistent behaviour which needed to use event transformations (encryption/decryption etc)
// during persistence and recovery. This just helps to avoid repeat some code in each behaviour
// and helps the behaviour code to look clean.

object EventSourcedBehaviorBuilder {

  def default[C,E,S](persId: PersistenceId,
                     emptyState: S,
                     commandHandler: EventTransformer => (S, C) => ReplyEffect[E, S],
                     eventHandler: (S, E) => S): EventSourcedBehaviorBuilder[C,E,S] = {
    EventSourcedBehaviorBuilder(persId, emptyState, commandHandler, eventHandler)
  }
}

case class EventSourcedBehaviorBuilder[C,E,S](persId: PersistenceId,
                                              emptyState: S,
                                              commandHandler: EventTransformer => (S, C) => ReplyEffect[E, S],
                                              eventHandler: (S, E) => S,
                                              objectCodeMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper,
                                              signalHandler: PartialFunction[(S, Signal), Unit] = PartialFunction.empty,
                                              enforcedReplies: Boolean = true,
                                              encryptionKey: Option[String] = None) {

  def withEventCodeMapper(objectCodeMapper: ObjectCodeMapperBase): EventSourcedBehaviorBuilder[C,E,S] =
    copy(objectCodeMapper = objectCodeMapper)

  def withEnforcedReplies(enforceReply: Boolean): EventSourcedBehaviorBuilder[C,E,S] =
    copy(enforcedReplies = enforceReply)

  def withEncryptionKey(key: String): EventSourcedBehaviorBuilder[C,E,S] =
    copy(encryptionKey = Option(key))

  def withSignalHandler(handler: PartialFunction[(S, Signal), Unit]): EventSourcedBehaviorBuilder[C,E,S] =
    copy(signalHandler = handler)

  def build(): EventSourcedBehavior[C, E, S] = {
    val eventTransformer = EventTransformer(persId, encryptionKey.getOrElse(persId.id), objectCodeMapper)
    val baseBehavior = if (enforcedReplies) {
      EventSourcedBehavior
        .withEnforcedReplies(
          persId,
          emptyState,
          commandHandler(eventTransformer),
          eventTransformer.transformedEventHandler(eventHandler)
        )
    } else {
      EventSourcedBehavior(
        persId,
        emptyState,
        commandHandler(eventTransformer),
        eventTransformer.transformedEventHandler(eventHandler)
      )
    }
    baseBehavior.receiveSignal(signalHandler)
  }
}
