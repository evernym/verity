package com.evernym.verity.actor.typed.base

import akka.actor.typed.Signal
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}


object EventSourcedBehaviorBuilder {

  def default[C,E,S](persId: PersistenceId,
                     emptyState: S,
                     commandHandler: BehaviourUtil => (S, C) => ReplyEffect[E, S],
                     eventHandler: (S, E) => S): EventSourcedBehaviorBuilder[C,E,S] = {
    EventSourcedBehaviorBuilder(persId, emptyState, commandHandler, eventHandler)
  }
}

case class EventSourcedBehaviorBuilder[C,E,S](persId: PersistenceId,
                                              emptyState: S,
                                              commandHandler: BehaviourUtil => (S, C) => ReplyEffect[E, S],
                                              eventHandler: (S, E) => S,
                                              objectCodeMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper,
                                              signalHandler: BehaviourUtil => PartialFunction[(S, Signal), Unit] = { _: BehaviourUtil => PartialFunction.empty},
                                              enforcedReplies: Boolean = true,
                                              encryptionKey: Option[String] = None) {

  def withObjectCodeMapper(objectCodeMapper: ObjectCodeMapperBase): EventSourcedBehaviorBuilder[C,E,S] =
    copy(objectCodeMapper = objectCodeMapper)

  def withEnforcedReplies(enforceReply: Boolean): EventSourcedBehaviorBuilder[C,E,S] =
    copy(enforcedReplies = enforceReply)

  def withEncryptionKey(key: String): EventSourcedBehaviorBuilder[C,E,S] =
    copy(encryptionKey = Option(key))

  def withSignalHandler(handler: BehaviourUtil => PartialFunction[(S, Signal), Unit]): EventSourcedBehaviorBuilder[C,E,S] =
    copy(signalHandler = handler)

  def build(): EventSourcedBehavior[C, E, S] = {
    val util = BehaviourUtil(persId, encryptionKey.getOrElse(persId.id), objectCodeMapper)
    val baseBehavior = if (enforcedReplies) {
      EventSourcedBehavior
        .withEnforcedReplies(
          persId,
          emptyState,
          commandHandler(util),
          util.eventHandlerWrapper(eventHandler)
        )
    } else {
      EventSourcedBehavior(
        persId,
        emptyState,
        commandHandler(util),
        util.eventHandlerWrapper(eventHandler)
      )
    }
    baseBehavior.receiveSignal(signalHandler(util))
  }
}
