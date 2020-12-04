package com.evernym.verity.actor.agent.msghandler

import akka.actor.ActorRef
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.maintenance.InitialActorState
import com.evernym.verity.actor.agent.msgrouter.ActorAddressDetail
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.protocol.actor.{ActorProtocol, SetThreadContext, ThreadContextNotStoredInProtoActor, ThreadContextStoredInProtoActor}
import com.evernym.verity.protocol.engine.{DID, PinstId, PinstIdResolution, ProtoRef}

import scala.concurrent.Future


/**
 * contains code related to thread context migration and making sure proper routes are setup
 */
trait AgentStateCleanupHelper { this: AgentMsgHandler with AgentPersistentActor =>

  def cleanupCmdHandler: Receive = {
    case FixActorState(did, sndrActorRef)         => fixActorState(did, sndrActorRef)
    case CheckActorStateCleanupState              => checkActorStateCleanupState()
    case MigrateThreadContexts                    => migrateThreadContexts()
    case rss: RouteSetStatus                      => handleRouteSet(rss)
    case tcm: ThreadContextStoredInProtoActor     => handleThreadContextMigrated(tcm.pinstId, tcm.protoRef)
    case tcnm: ThreadContextNotStoredInProtoActor => handleThreadContextNotMigrated(tcnm.pinstId, tcnm.protoRef)
  }

  def cleanupEventReceiver: Receive = {
    case tcmc: ThreadContextMigrationStarted =>
      if (tcmc.candidateProtoActors.nonEmpty) {
        val protoRefs = tcmc.candidateProtoActors.map(ProtoRef.fromString).toSet
        threadContextMigrationStatus += (tcmc.pinstId -> ThreadContextMigrationStatus(protoRefs, Set.empty, Set.empty))
      }

    case tcmc: ThreadContextMigrationStatusUpdated =>
      threadContextMigrationStatus.get(tcmc.pinstId).foreach { migrationStatus =>
        val updatedStatus = migrationStatus.copy(
          successResponseFromProtoActors = tcmc.successResponseFromProtoActors.map(ProtoRef.fromString).toSet,
          nonSuccessResponseFromProtoActors = tcmc.nonSuccessResponseFromProtoActors.map(ProtoRef.fromString).toSet)
        if (updatedStatus.isAllRespReceived) {
          removeThreadContext(tcmc.pinstId)
          threadContextMigrationStatus -= tcmc.pinstId
        } else {
          threadContextMigrationStatus += (tcmc.pinstId -> updatedStatus)
        }
      }
  }

  def handleRouteSet(rss: RouteSetStatus): Unit = {
    routeSetStatus = Option(rss)
    checkActorStateCleanupState()
  }

  def handleThreadContextMigrated(pinstId: PinstId, protoRef: ProtoRef): Unit = {
    threadContextMigrationStatus.get(pinstId).foreach { migrationStatus =>
      val successResp = (migrationStatus.successResponseFromProtoActors + protoRef).map(_.toString).toSeq
      val nonSuccessResp = migrationStatus.nonSuccessResponseFromProtoActors.map(_.toString).toSeq
      writeAndApply(ThreadContextMigrationStatusUpdated(pinstId, successResp, nonSuccessResp))
    }
    sendCheckActorStateCleanupState()
  }

  def handleThreadContextMigrationAttemptExceeded(attempt: Int, pinstId: PinstId, protoRef: ProtoRef): Unit = {
    logger.warn(s"[$persistenceId] max attempt ($attempt) exceeded for thread context migration for " +
      s"pinst $pinstId and proto ref $protoRef")
    handleThreadContextNotMigrated(pinstId, protoRef)
  }

  def handleThreadContextNotMigrated(pinstId: PinstId, protoRef: ProtoRef): Unit = {
    threadContextMigrationStatus.get(pinstId).foreach { curStatus =>
      val successResp = curStatus.successResponseFromProtoActors.map(_.toString).toSeq
      val nonSuccessResp = (curStatus.nonSuccessResponseFromProtoActors + protoRef).map(_.toString).toSeq
      val event = ThreadContextMigrationStatusUpdated(pinstId, successResp, nonSuccessResp)
      if (curStatus.candidateProtoActors.size == nonSuccessResp.size) {
        writeAndApply(event)
      } else {
        applyEvent(event)
      }
    }
    sendCheckActorStateCleanupState()
  }

  def migrateThreadContexts(): Unit = {
    if (isMigrateThreadContextsEnabled) {
      val candidateThreadContexts =
        state.threadContext.map(_.contexts).getOrElse(Map.empty)
          .take(migrateThreadContextBatchSize)
      if (candidateThreadContexts.isEmpty && routeSetStatus.forall(_.isSet)) {
        finishThreadContextMigration()
      } else {
        candidateThreadContexts.foreach { case (pinstId, tcd) =>
          val candidateProtoActors = com.evernym.verity.protocol.protocols.protocolRegistry.entries.map { e =>
            val migrationStatus = threadContextMigrationStatus.get(pinstId)
            val successResp = migrationStatus.map(_.successResponseFromProtoActors).getOrElse(Set.empty)
            val nonSuccessResp = migrationStatus.map(_.nonSuccessResponseFromProtoActors).getOrElse(Set.empty)
            val respReceivedFromProtoActors = successResp ++ nonSuccessResp

            if (! respReceivedFromProtoActors.contains(e.protoDef.msgFamily.protoRef)) {
              val pinstProtoRefStr = pinstId + e.protoDef.msgFamily.protoRef.toString
              val currAttempt = threadContextMigrationAttempt.getOrElse(pinstProtoRefStr, 0)
              if (currAttempt < migrateThreadContextMaxAttemptPerPinstProtoRef) {
                val calcPinstId = e.pinstIdResol.resolve(e.protoDef, domainId, relationshipId, Option(tcd.threadId), None, contextualId)
                if (e.pinstIdResol == PinstIdResolution.DEPRECATED_V0_1 || pinstId == calcPinstId) {
                  threadContextMigrationAttempt += (pinstProtoRefStr -> (currAttempt + 1))
                  val cmd = ForIdentifier(pinstId, SetThreadContext(tcd))
                  e -> Option(cmd)
                } else e -> None
              } else {
                handleThreadContextMigrationAttemptExceeded(currAttempt, pinstId, e.protoDef.msgFamily.protoRef)
                e -> None
              }
            } else e -> None
          }.filter(_._2.isDefined).map(r => r._1 -> r._2.get)

          if (! threadContextMigrationStatus.contains(pinstId)) {
            val deprecatedV01Count = candidateProtoActors.map(_._1.pinstIdResol).count(_ == PinstIdResolution.DEPRECATED_V0_1)
            val v02Count = candidateProtoActors.map(_._1.pinstIdResol).count(_ == PinstIdResolution.V0_2)
            logger.debug(s"[$persistenceId] thread context migration candidates for pinst $pinstId => total: ${candidateProtoActors.size} (DEPRECATED_V01: $deprecatedV01Count, V02: $v02Count)")
            val event = ThreadContextMigrationStarted(pinstId, candidateProtoActors.map(_._1.protoDef.msgFamily.protoRef.toString))
            applyEvent(event)
            writeWithoutApply(event)
          }

          Future {
            candidateProtoActors.foreach { case (e, cmd) =>
              ActorProtocol(e.protoDef).region.tell(cmd, self)
              java.lang.Thread.sleep(migrateThreadContextBatchItemSleepInterval)
            }
          }
        }
      }
    } else {
      stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
    }
  }

  def fixActorState(did: DID, sndrActorRef: ActorRef): Unit = {
    if (routeSetStatus.isEmpty) {
      actorStateCleanupExecutor = Option(sndrActorRef)
      routeSetStatus = Option(RouteSetStatus(did, isSet = false))
      sndrActorRef ! InitialActorState(did, state.threadContext.map(_.contexts.size).getOrElse(0))
      state.myDid.map(myDID => setRoute(myDID))
      sendCheckActorStateCleanupState()
      migrateThreadContexts()
    }
  }

  def sendCheckActorStateCleanupState(): Unit = {
    val pendingThreadContexts = state.threadContext.map(tc => tc.contexts).getOrElse(Map.empty)
    if (pendingThreadContexts.size <= 1) {
      val isReadyToCheckState = pendingThreadContexts.keySet.headOption.forall { pinstId =>
        threadContextMigrationStatus.get(pinstId).exists { v =>
          v.candidateProtoActors.size -1 == v.successResponseFromProtoActors.size + v.nonSuccessResponseFromProtoActors.size
        }
      }
      if (isReadyToCheckState)
        self ! CheckActorStateCleanupState
    }
  }

  def checkActorStateCleanupState(): Unit = {
    val pendingThreadContextSize = state.threadContext.map(tc => tc.contexts.size).getOrElse(0)
    if (pendingThreadContextSize == 0) {
      routeSetStatus.foreach { rss =>
        state.myDid match {
          case Some(did) if did != rss.did && !routeSetStatus.exists(_.isSet) =>
            getRoute(did).mapTo[Option[ActorAddressDetail]].map { r =>
              if (r.isDefined) {
                self ! RouteSetStatus(rss.did, isSet = true)
                postActorStateCleanup(rss.did)
              }
            }
          case _ => postActorStateCleanup(rss.did)
        }
      }
    }
  }

  def postActorStateCleanup(did: DID): Unit = {
    actorStateCleanupExecutor.foreach { sndr =>
      sndr ! ActorStateCleanupStatus(
        did,
        isRouteFixed = true,
        isAllThreadContextMigrated = true,
        threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size == 1),
        threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size != 1))
      finishThreadContextMigration()
    }
  }

  def finishThreadContextMigration(): Unit = {
    stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
    threadContextMigrationStatus = Map.empty
    threadContextMigrationAttempt = Map.empty
    actorStateCleanupExecutor = None
    routeSetStatus = None

  }

  type ResponseReceived = Boolean
  type PinstProtoRefStr = String

  var threadContextMigrationStatus: Map[PinstId, ThreadContextMigrationStatus] = Map.empty
  var threadContextMigrationAttempt: Map[PinstProtoRefStr, Int] = Map.empty

  var actorStateCleanupExecutor: Option[ActorRef] = None
  var routeSetStatus: Option[RouteSetStatus] = None

  lazy val migrateThreadContextBatchSize: Int =
    appConfig
      .getConfigIntOption(MIGRATE_THREAD_CONTEXTS_BATCH_SIZE)
      .getOrElse(5)

  lazy val migrateThreadContextBatchItemSleepInterval: Int =
    appConfig
      .getConfigIntOption(MIGRATE_THREAD_CONTEXTS_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5000)

  lazy val migrateThreadContextScheduledJobInitialDelay: Int =
    appConfig
      .getConfigIntOption(MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB_INITIAL_DELAY_IN_SECONDS)
      .getOrElse(60)

  lazy val migrateThreadContextScheduledJobInterval: Int =
    appConfig
      .getConfigIntOption(MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  val MIGRATE_SCHEDULED_JOB_ID = "MigrateThreadContexts"

  lazy val migrateThreadContextMaxAttemptPerPinstProtoRef: Int =
    appConfig
      .getConfigIntOption(CommonConfig.MIGRATE_THREAD_CONTEXTS_MAX_ATTEMPT_PER_PINST_PROTO_REF)
      .getOrElse(15)

  def isMigrateThreadContextsEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.MIGRATE_THREAD_CONTEXTS_ENABLED)
      .getOrElse(false)


  scheduleJob(
    MIGRATE_SCHEDULED_JOB_ID,
    migrateThreadContextScheduledJobInitialDelay,
    migrateThreadContextScheduledJobInterval,
    MigrateThreadContexts)
}

case object MigrateThreadContexts extends ActorMessageObject

case class FixActorState(actorID: DID, senderActorRef: ActorRef) extends ActorMessageClass
case object CheckActorStateCleanupState extends ActorMessageClass
case class ActorStateCleanupStatus(actorID: DID,
                                   isRouteFixed: Boolean,
                                   isAllThreadContextMigrated: Boolean,
                                   successfullyMigratedCount: Int,
                                   nonMigratedCount: Int) extends ActorMessageClass {
  def isStateCleanedUp: Boolean = isRouteFixed && isAllThreadContextMigrated
}

case class ThreadContextMigrationStatus(candidateProtoActors: Set[ProtoRef],
                                        successResponseFromProtoActors: Set[ProtoRef],
                                        nonSuccessResponseFromProtoActors: Set[ProtoRef]) {
  def isAllRespReceived: Boolean = candidateProtoActors.size == (successResponseFromProtoActors.size + nonSuccessResponseFromProtoActors.size)
}

case class RouteSetStatus(did: DID, isSet: Boolean) extends ActorMessageObject