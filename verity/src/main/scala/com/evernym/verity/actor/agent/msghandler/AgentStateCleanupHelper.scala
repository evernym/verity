package com.evernym.verity.actor.agent.msghandler

import akka.actor.ActorRef
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.ThreadContextDetail
import com.evernym.verity.actor.agent.maintenance.InitialActorState
import com.evernym.verity.actor.agent.msgrouter.RouteAlreadySet
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.config.ConfigConstants
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.observability.logs.ThrottledLogger
import com.evernym.verity.protocol.container.actor._
import com.evernym.verity.protocol.engine.registry.PinstIdResolution
import com.evernym.verity.protocol.engine.{PinstId, ProtoRef}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessageDefinition
import com.evernym.verity.util2.Exceptions

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


/**
 * contains code related to thread context migration and making sure proper routes are setup
 */
trait AgentStateCleanupHelper {
  this: AgentMsgHandler with AgentPersistentActor =>

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def cleanupCmdHandler: Receive = {
    case FixThreadMigrationState                  => fixThreadMigrationState()
    case MigrateThreadContexts                    => migrateThreadContextsByScheduledJob()
    case FixActorState(did, sndrActorRef)         => fixActorState(did, sndrActorRef)
    case cs: CheckActorStateCleanupState          => checkActorStateCleanupState(cs.sendCurrentStatus)
    case rss: RouteSetStatus                      => handleRouteSet(rss)
    case tcm: ThreadContextStoredInProtoActor     => handleThreadContextMigrated(tcm.pinstId, tcm.protoRef)
    case tcnm: ThreadContextNotStoredInProtoActor => handleThreadContextNotMigrated(tcnm.pinstId, tcnm.protoRef)
    case sc: SendCmd                              => sc.to.tell(sc.cmd, self)
  }

  /**
   * this is because in few cases until the agent actor completely recovers,
   * it doesn't have all members of state evaluated and calculation of pinst id fails,
   * hence this function would be called once per actor start (immediately after all recovered events applied)
   */
  def fixThreadMigrationState(): Unit = {
    //only proceed if there are any pending thread context to be migrated
    if (getPendingThreadContextSize > 0) {
      threadContextMigrationStatus.foreach { case (pinstId, tcms) =>
        //only proceed if thread context exists for the given pinst id
        getThreadContexts.get(pinstId).foreach { _ =>
          val calcPinstId = getCalcPinstIdForBasicMsgProtoDef(pinstId)
          val updatedStatus = tcms.copy(
            candidateProtoActors = buildProtoRefs(pinstId, tcms.candidateProtoActors, Option(calcPinstId)),
            successResponseFromProtoActors = buildProtoRefs(pinstId, tcms.successResponseFromProtoActors, Option(calcPinstId)),
            nonSuccessResponseFromProtoActors = buildProtoRefs(pinstId, tcms.nonSuccessResponseFromProtoActors, Option(calcPinstId))
          )
          threadContextMigrationStatus += pinstId -> updatedStatus
          if (updatedStatus.isAllRespReceived) {
            removeThreadContext(pinstId)
          }
        }
      }
    }
  }

  def getCalcPinstIdForBasicMsgProtoDef(pinstId: PinstId): PinstId = {
    val tcd = state.currentThreadContexts.get(pinstId)
    PinstIdResolution.V0_2.resolve(BasicMessageDefinition, domainId, relationshipId, tcd.map(_.threadId), None, state.thisAgentKeyDID)
  }

  def buildProtoRefs(pinstId: PinstId, protoRefStrs: Seq[String]): Set[ProtoRef] = {
    val protoRefSet = protoRefStrs.map(ProtoRef.fromString).toSet
    buildProtoRefs(pinstId, protoRefSet)
  }

  def buildProtoRefs(pinstId: PinstId, protoRefSet: Set[ProtoRef], calcPinstIdOpt: Option[PinstId]=None): Set[ProtoRef] = {
    if (! isSuccessfullyRecovered) {
      //meaning this code is executing while actor is recovering existing events
      protoRefSet
    } else {
      val calcPinstId = calcPinstIdOpt.getOrElse(getCalcPinstIdForBasicMsgProtoDef(pinstId))
      if (pinstId == calcPinstId) protoRefSet
      else protoRefSet - BasicMessageDefinition.protoRef
    }
  }

  def cleanupEventReceiver: Receive = {
    case tcms: ThreadContextMigrationStarted =>
      if (tcms.candidateProtoActors.nonEmpty) {
        val protoRefs = buildProtoRefs(tcms.pinstId, tcms.candidateProtoActors)
        threadContextMigrationStatus += (tcms.pinstId -> ThreadContextMigrationStatus(protoRefs, Set.empty, Set.empty))
      }

    case tcmsu: ThreadContextMigrationStatusUpdated =>
      threadContextMigrationStatus.get(tcmsu.pinstId).foreach { curStatus =>
        val updatedStatus = curStatus.copy(
          successResponseFromProtoActors = buildProtoRefs(tcmsu.pinstId, tcmsu.successResponseFromProtoActors),
          nonSuccessResponseFromProtoActors = buildProtoRefs(tcmsu.pinstId, tcmsu.nonSuccessResponseFromProtoActors))
        if (updatedStatus.isAllRespReceived) {
          removeThreadContext(tcmsu.pinstId)
        }
        threadContextMigrationStatus += (tcmsu.pinstId -> updatedStatus)
      }
  }

  def handleRouteSet(rss: RouteSetStatus): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCH->ASCH] received routeSetStatus: " + routeSetStatus)
    routeSetStatus = Option(rss)
    checkActorStateCleanupState(forceSendCurStatus = true)
  }

  def handleThreadContextMigrated(pinstId: PinstId, protoRef: ProtoRef): Unit = {
    handleThreadContextMigrationStateChange(pinstId, Option(protoRef))
  }

  def handleThreadContextNotMigrated(pinstId: PinstId, protoRef: ProtoRef): Unit = {
    handleThreadContextMigrationStateChange(pinstId, None, Option(protoRef))
  }

  def handleThreadContextMigrationAttemptExceeded(attempt: Int, pinstId: PinstId, protoRef: ProtoRef): Unit = {
    logger.warn(s"[$persistenceId] max attempt ($attempt) exceeded for thread context migration for " +
      s"pinst $pinstId and proto ref $protoRef")
    handleThreadContextNotMigrated(pinstId, protoRef)
  }

  def handleThreadContextMigrationStateChange(pinstId: PinstId,
                                              migratedToProtoRef: Option[ProtoRef]=None,
                                              notMigratedToProtoRef: Option[ProtoRef]=None): Unit = {
    threadContextMigrationStatus.get(pinstId).foreach { curStatus =>
      if (! curStatus.isAllRespReceived) {
        val successResp = (curStatus.successResponseFromProtoActors ++ migratedToProtoRef).map(_.toString).toSeq
        val nonSuccessResp = (curStatus.nonSuccessResponseFromProtoActors ++ notMigratedToProtoRef).map(_.toString).toSeq
        val totalResp = successResp ++ nonSuccessResp
        val event = ThreadContextMigrationStatusUpdated(pinstId, successResp, nonSuccessResp)
        cleanupEventReceiver(event)
        checkActorStateCleanupState(forceSendCurStatus = false)
        if (curStatus.candidateProtoActors.size == totalResp.size) {
          writeAndApply(event)
        }
      }
    }
  }

  def migrateThreadContexts(): Unit = {
    try {
      throttledLogger.info(
        MigrateThreadContextsReceived(s"$persistenceId-1"),
        s"ASC [$persistenceId] [ASCH->ASCH] received migrateThreadContext")
      if (isMigrateThreadContextsEnabled && !isThreadContextMigrationFinished) {
        val candidateThreadContexts = state.currentThreadContexts.take(migrateThreadContextBatchSize)
        if (candidateThreadContexts.isEmpty && routeSetStatus.forall(_.isSet)) {
          finishThreadContextMigration()
        } else {
          scheduleThreadContextMigrationJobIfNotScheduled()
          candidateThreadContexts.foreach { case (pinstId, tcd) =>
            val candidateProtoActors = com.evernym.verity.protocol.protocols.protocolRegistry.entries.map { e =>
              val migrationStatus = threadContextMigrationStatus.get(pinstId)
              val successResp = migrationStatus.map(_.successResponseFromProtoActors).getOrElse(Set.empty)
              val nonSuccessResp = migrationStatus.map(_.nonSuccessResponseFromProtoActors).getOrElse(Set.empty)
              val respReceivedFromProtoActors = successResp ++ nonSuccessResp
              if (!respReceivedFromProtoActors.contains(e.protoDef.protoRef)) {
                val pinstProtoRefStr = pinstId + e.protoDef.msgFamily
                val currAttempt = threadContextMigrationAttempt.getOrElse(pinstProtoRefStr, 0)
                if (currAttempt < migrateThreadContextMaxAttemptPerPinstProtoRef) {
                  throttledLogger.info(
                    MigrateThreadContextsReceived(s"$persistenceId-2a"),
                    s"ASC [$persistenceId] [ASCH->ASCH] max attempt NOT reached: " + pinstProtoRefStr)
                  val calcPinstId = e.pinstIdResol.resolve(e.protoDef, domainId, relationshipId, Option(tcd.threadId), None, state.thisAgentKeyDID)
                  if (e.pinstIdResol == PinstIdResolution.DEPRECATED_V0_1 || pinstId == calcPinstId) {
                    threadContextMigrationAttempt += (pinstProtoRefStr -> (currAttempt + 1))
                    val cmd = ForIdentifier(
                      pinstId,
                      ProtocolCmd(SetThreadContext(tcd), None)
                    )
                    e -> Option(cmd)
                  } else e -> None
                } else {
                  throttledLogger.info(
                    MigrateThreadContextsReceived(s"$persistenceId-2b"),
                    s"ASC [$persistenceId] [ASCH->ASCH] max attempt reached: " + pinstProtoRefStr)
                  handleThreadContextMigrationAttemptExceeded(currAttempt, pinstId, e.protoDef.protoRef)
                  e -> None
                }
              } else e -> None
            }.filter(_._2.isDefined).map(r => r._1 -> r._2.get)

            if (!threadContextMigrationStatus.contains(pinstId)) {
              val deprecatedV01Count = candidateProtoActors.map(_._1.pinstIdResol).count(_ == PinstIdResolution.DEPRECATED_V0_1)
              val v02Count = candidateProtoActors.map(_._1.pinstIdResol).count(_ == PinstIdResolution.V0_2)
              throttledLogger.info(
                MigrateThreadContextsReceived(s"$persistenceId-3"),
                s"[$persistenceId] thread context migration candidates for pinst $pinstId => total: ${candidateProtoActors.size} (DEPRECATED_V01: $deprecatedV01Count, V02: $v02Count)")
              val event = ThreadContextMigrationStarted(pinstId, candidateProtoActors.map(_._1.protoDef.protoRef.toString))
              cleanupEventReceiver(event)
              writeWithoutApply(event)
            }

            throttledLogger.info(
              MigrateThreadContextsReceived(s"$persistenceId-4"),
              s"ASC [$persistenceId] [ASCH->ASCH] candidateProtoActors: " + candidateProtoActors.size)
            candidateProtoActors.zipWithIndex.foreach { case (entry, index) =>
              val key = entry._1.protoDef.protoRef.toString + entry._2.id
              val toActor = ActorProtocol(entry._1.protoDef).region(context.system)
              val timeout = Duration(migrateThreadContextBatchItemSleepInterval * index, MILLISECONDS)
              timers.startSingleTimer(key, SendCmd(toActor, entry._2), timeout)
            }
          }
        }
      } else {
        stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
        throttledLogger.info(
          MigrateThreadContextsReceived(s"$persistenceId-5"),
          s"ASC [$persistenceId] [ASCH->ASCH] migrateThreadContext job stopped")
      }
    } catch {
      case e: Throwable =>
        logger.error("error while thread context migration: " + Exceptions.getStackTraceAsSingleLineString(e))
    }
  }

  def fixActorState(did: DidStr, sndrActorRef: ActorRef): Unit = {
    throttledLogger.info(
      FixActorStateReceived(persistenceId),
      s"[$persistenceId] received fixActorState (" +
        s"did: $did, domainDID: $domainId, " +
        s"pending: $getPendingThreadContextSize, " +
        s"routeSetStatus: $routeSetStatus, " +
        s"isStateCleanupCompleted: $isStateCleanupCompleted, " +
        s"isThreadContextMigrationFinished: $isThreadContextMigrationFinished"
    )

    actorStateCleanupExecutor = Option(sndrActorRef)

    if (routeSetStatus.isEmpty) {
      isStateCleanupCompleted = false
      val isRouteSet = state.myDid.isEmpty || ! state.myDid.contains(did)
      routeSetStatus = Option(RouteSetStatus(did, isSet = isRouteSet))
      sndrActorRef ! InitialActorState(did, isRouteSet, getTotalThreadContextSize)
      state.myDid.foreach { myDID =>
        if (! isRouteSet) {
          setRoute(myDID).map {
            case _: RouteSet | _:RouteAlreadySet =>
              self ! RouteSetStatus(did, isSet = true)
          }
        }
      }
      migrateThreadContexts()
    }
    checkActorStateCleanupState(forceSendCurStatus = true)
  }

  def checkActorStateCleanupState(forceSendCurStatus: Boolean): Unit = {
    val pendingThreadContextSize = getPendingThreadContextSize
    logger.debug(s"ASC [$persistenceId] [ASCH->ASCH] called checkActorStateCleanupState =>" +
      " forceSendCurStatus: " + forceSendCurStatus +
      ", pendingThreadContextSize: " + pendingThreadContextSize +
      ", routeSetStatus: " + routeSetStatus)
    routeSetStatus match {
      case Some(rss) if forceSendCurStatus || pendingThreadContextSize == 0 =>
        actorStateCleanupExecutor.foreach { sndr =>
          sndr ! ActorStateCleanupStatus(
            rss.did,
            isRouteFixed = rss.isSet,
            pendingThreadContextSize,
            threadContextMigrationStatus.count(_._2.isSuccessfullyMigrated),
            threadContextMigrationStatus.count(_._2.isNotMigrated))
          if (rss.isSet && pendingThreadContextSize == 0) {
            finishThreadContextMigration()
            finishActorStateCleanup()
          }
        }
      case _ => //nothing to do
    }
  }

  def migrateThreadContextsByScheduledJob(): Unit = {
    migrateThreadContexts()
  }

  def getTotalThreadContextSize: Int = getMigratedThreadContextSize + getPendingThreadContextSize

  def getMigratedThreadContextSize: Int = threadContextMigrationStatus.count(_._2.isAllRespReceived)

  def getThreadContexts: Map[PinstId, ThreadContextDetail] = state.currentThreadContexts

  def getPendingThreadContextSize: Int = getThreadContexts.size

  def finishThreadContextMigration(): Unit = {
    isThreadContextMigrationFinished = true
    stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
    threadContextMigrationStatus = Map.empty
    threadContextMigrationAttempt = Map.empty
    if (! isSnapshotTaken) {
      saveSnapshotStateIfAvailable()
      isSnapshotTaken = true
    }
  }

  def finishActorStateCleanup(): Unit = {
    if (! isStateCleanupCompleted) {
      actorStateCleanupExecutor = None
      routeSetStatus = None
      isStateCleanupCompleted = true
    }
  }

  var isSnapshotTaken = false
  var isStateCleanupCompleted = false
  type ResponseReceived = Boolean
  type PinstProtoRefStr = String

  var threadContextMigrationStatus: Map[PinstId, ThreadContextMigrationStatus] = Map.empty
  var threadContextMigrationAttempt: Map[PinstProtoRefStr, Int] = Map.empty

  var actorStateCleanupExecutor: Option[ActorRef] = None
  var routeSetStatus: Option[RouteSetStatus] = None

  lazy val migrateThreadContextBatchSize: Int =
    appConfig
      .getIntOption(MIGRATE_THREAD_CONTEXTS_BATCH_SIZE)
      .getOrElse(5)

  lazy val migrateThreadContextBatchItemSleepInterval: Int =
    appConfig
      .getIntOption(MIGRATE_THREAD_CONTEXTS_BATCH_ITEM_SLEEP_INTERVAL_IN_MILLIS)
      .getOrElse(5000)

  lazy val migrateThreadContextScheduledJobInterval: Int =
    appConfig
      .getIntOption(MIGRATE_THREAD_CONTEXTS_SCHEDULED_JOB_INTERVAL_IN_SECONDS)
      .getOrElse(300)

  val MIGRATE_SCHEDULED_JOB_ID = "MigrateThreadContexts"

  lazy val migrateThreadContextMaxAttemptPerPinstProtoRef: Int =
    appConfig
      .getIntOption(ConfigConstants.MIGRATE_THREAD_CONTEXTS_MAX_ATTEMPT_PER_PINST_PROTO_REF)
      .getOrElse(10)

  def isMigrateThreadContextsEnabled: Boolean =
    appConfig
      .getBooleanOption(ConfigConstants.MIGRATE_THREAD_CONTEXTS_ENABLED)
      .getOrElse(false)

  def scheduleThreadContextMigrationJobIfNotScheduled(): Unit = {
    scheduleJob(
      MIGRATE_SCHEDULED_JOB_ID,
      migrateThreadContextScheduledJobInterval,
      MigrateThreadContexts)
  }

  //scheduleThreadContextMigrationJobIfNotScheduled()

  //self ! FixThreadMigrationState

  private val throttledLogger = new ThrottledLogger[ActorStateCleanupHelper](logger, min_period = 10.minutes)
}

case object MigrateThreadContexts extends ActorMessage
case object FixThreadMigrationState extends ActorMessage
case class FixActorState(actorDID: DidStr, senderActorRef: ActorRef) extends ActorMessage
case class CheckActorStateCleanupState(sendCurrentStatus: Boolean = false) extends ActorMessage
case class ActorStateCleanupStatus(actorDID: DidStr,
                                   isRouteFixed: Boolean,
                                   pendingCount: Int,
                                   successfullyMigratedCount: Int,
                                   nonMigratedCount: Int) extends ActorMessage {
  def totalProcessed: Int = successfullyMigratedCount + nonMigratedCount
}

case class ThreadContextMigrationStatus(candidateProtoActors: Set[ProtoRef],
                                        successResponseFromProtoActors: Set[ProtoRef],
                                        nonSuccessResponseFromProtoActors: Set[ProtoRef]) {
  def isAllRespReceived: Boolean = candidateProtoActors.size ==
    (successResponseFromProtoActors.size + nonSuccessResponseFromProtoActors.size)
  def isSuccessfullyMigrated: Boolean = successResponseFromProtoActors.nonEmpty
  def isNotMigrated: Boolean = isAllRespReceived && successResponseFromProtoActors.isEmpty
}

case class RouteSetStatus(did: DidStr, isSet: Boolean) extends ActorMessage

sealed trait ActorStateCleanupHelper
case class FixActorStateReceived(persistenceId: String) extends ActorStateCleanupHelper
case class MigrateThreadContextsReceived(persistenceId: String) extends ActorStateCleanupHelper