package com.evernym.verity.actor.typed.poc

import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}

object EventSourcedBehaviorBuilder {

  def withPersistenceHandler[C,E,S](persId: PersistenceId,
                                    emptyState: S,
                                    commandHandler: PersistenceHandler => (S, C) => ReplyEffect[E, S],
                                    eventHandler: (S, E) => S): EventSourcedBehaviorBuilder[C,E,S] = {
    EventSourcedBehaviorBuilder(persId, emptyState, commandHandler, eventHandler)
  }
}

case class EventSourcedBehaviorBuilder[C,E,S](persId: PersistenceId,
                                              emptyState: S,
                                              commandHandler: PersistenceHandler => (S, C) => ReplyEffect[E, S],
                                              eventHandler: (S, E) => S,
                                              objectCodeMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper,
                                              enforcedReplies: Boolean = true,
                                              encryptionKey: Option[String] = None) {

  def withObjectCodeMapper(objectCodeMapper: ObjectCodeMapperBase): EventSourcedBehaviorBuilder[C,E,S] =
    copy(objectCodeMapper = objectCodeMapper)

  def withEnforcedReplies(enforceReply: Boolean): EventSourcedBehaviorBuilder[C,E,S] =
    copy(enforcedReplies = enforceReply)

  def withEncryptionKey(key: String): EventSourcedBehaviorBuilder[C,E,S] =
    copy(encryptionKey = Option(key))

  def build(): EventSourcedBehavior[C, E, S] = {
    val persistenceHandler = new PersistenceHandler(persId, encryptionKey.getOrElse(persId.id), objectCodeMapper)
    if (enforcedReplies) {
      EventSourcedBehavior
        .withEnforcedReplies(
          persId,
          emptyState,
          commandHandler(persistenceHandler),
          persistenceHandler.eventHandlerWrapper(eventHandler)
        )
    } else {
      EventSourcedBehavior(
        persId,
        emptyState,
        commandHandler(persistenceHandler),
        persistenceHandler.eventHandlerWrapper(eventHandler)
      )
    }
  }
}