//package com.evernym.verity.actor.msgoutbox.outbox
//
//import akka.actor.typed.{Behavior, Signal}
//import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
//import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
//import akka.persistence.typed.{DeleteSnapshotsFailed, PersistenceId, RecoveryCompleted, SnapshotFailed}
//import akka.persistence.typed.scaladsl.{EventSourcedBehavior, ReplyEffect, RetentionCriteria}
//
//import com.evernym.verity.actor.agent.user.ComMethodDetail
//import com.evernym.verity.actor.typed.base.EventPersistenceAdapter
//import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
//import com.typesafe.scalalogging.Logger
//
//object OutboxBehaviour {
//
//  trait Cmd
//  trait Event   //all events would be defined in outbox-events.proto file
//  trait State   //all states would be defined in outbox-states.proto file
//
//  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Outbox")
//
//  def apply(entityContext: EntityContext[Cmd],
//            walletId: String,
//            destination: Destination): Behavior[Cmd] = {
//    Behaviors.setup { context =>
//      EventSourcedBehavior
//        .withEnforcedReplies(
//          PersistenceId(TypeKey.name, entityContext.entityId),
//          States.Uninitialized,
//          commandHandler(context, entityContext.entityId, walletId, destination),
//          eventHandler)
//        .eventAdapter(new EventPersistenceAdapter(entityContext.entityId, EventObjectMapper))
//        //.receiveSignal(signalHandler(entityContext.entityId))   //TODO: enable this
//        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)) //TODO: finalize this
//    }
//  }
//
//  private def commandHandler(context: ActorContext[Cmd],
//                             outboxId: String,
//                             walletId: String,
//                             destination: Destination): (State, Cmd) => ReplyEffect[Event, State] = {
//    ???
//  }
//
//  private val eventHandler: (State, Event) => State = {
//    ???
//  }
//
//  private def signalHandler(outboxId: String): PartialFunction[(State, Signal), Unit] = {
//    case (st, RecoveryCompleted) =>
//      //TODO: fetch data from relationship actor and update state if required
//    case (st, sf: SnapshotFailed) =>
//      logger.error(s"[$outboxId] snapshot failed with error: " + sf.failure.getMessage)
//    case (st, dsf: DeleteSnapshotsFailed) =>
//      logger.error(s"[$outboxId] delete snapshot failed with error: " + dsf.failure.getMessage)
//  }
//
//  val logger: Logger = getLoggerByClass(getClass)
//
//  case class Destination(comMethods: Set[ComMethodDetail])
//}
