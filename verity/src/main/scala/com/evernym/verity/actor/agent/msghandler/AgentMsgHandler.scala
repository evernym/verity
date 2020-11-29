package com.evernym.verity.actor.agent.msghandler

import akka.actor.ActorRef
import com.evernym.verity.constants.Constants.UNKNOWN_SENDER_PARTICIPANT_ID
import com.evernym.verity.actor._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msghandler.incoming.AgentIncomingMsgHandler
import com.evernym.verity.actor.agent.msghandler.outgoing.{AgentOutgoingMsgHandler, OutgoingMsgParam}
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.actor.agent.maintenance.InitialActorState
import com.evernym.verity.actor.agent.msgrouter.ActorAddressDetail
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.agentmsg.msgcodec.UnknownFormatType
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgExtractor
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.protocol.actor.{ActorProtocol, InitProtocolReq, SetThreadContext, ThreadContextNotStoredInProtoActor, ThreadContextStoredInProtoActor}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.connecting.common.GetInviteDetail
import com.evernym.verity.util.MsgIdProvider
import com.evernym.verity.vault.KeyInfo

import scala.concurrent.Future
import scala.util.Left

/**
 * handles incoming and outgoing messages
 */

trait AgentMsgHandler
  extends AgentCommon
    with ActorLaunchesProtocol
    with ProtocolEngineExceptionHandler
    with AgentIncomingMsgHandler
    with AgentOutgoingMsgHandler
    with MsgTraceProvider
    with HasLogger {

  this: AgentPersistentActor =>

  def agentCommonCmdReceiver[A]: Receive = {
    case _: AgentActorDetailSet       => //nothing to do

    //actor protocol container (only if created for first time) -> this actor
    case ipr: InitProtocolReq         => handleInitProtocolReq(ipr)

    //TODO: below cmd handlers is only till when we run actor state cleanup (thread context migration + route fixing) job
    case FixActorState(did)                       => fixActorState(did)
    case CheckActorStateCleanupState(did)         => checkActorStateCleanupState(did)
    case MigrateThreadContexts                    => migrateThreadContexts()
    case tcsipa: ThreadContextStoredInProtoActor  =>
      threadContextMigrationStatus.get(tcsipa.pinstId).foreach { migrationStatus =>
        val successResp = (migrationStatus.successResponseFromProtoActors + tcsipa.protoRef).map(_.toString).toSeq
        val nonSuccessResp = migrationStatus.nonSuccessResponseFromProtoActors.map(_.toString).toSeq
        writeAndApply(ThreadContextMigrationStatusUpdated(tcsipa.pinstId, successResp, nonSuccessResp))
      }
    case tcns: ThreadContextNotStoredInProtoActor =>
      threadContextMigrationStatus.get(tcns.pinstId).foreach { curStatus =>
        val successResp = curStatus.successResponseFromProtoActors.map(_.toString).toSeq
        val nonSuccessResp = (curStatus.nonSuccessResponseFromProtoActors + tcns.protoRef).map(_.toString).toSeq
        val event = ThreadContextMigrationStatusUpdated(tcns.pinstId, successResp, nonSuccessResp)
        if (curStatus.candidateProtoActors.size == nonSuccessResp.size) {
          writeAndApply(event)
        } else {
          applyEvent(event)
        }
      }
  }

  override final def receiveCmd: Receive =
    agentCommonCmdReceiver orElse
      agentIncomingCommonCmdReceiver orElse
      agentOutgoingCommonCmdReceiver orElse
      receiveAgentCmd orElse {

      case m: ActorMessage => try {
        //these are the untyped incoming messages:
        // a. for example get invite message sent by invite acceptor (connect.me)
        // b. control messages sent by agent actors (in response to a signal message handling)
        //      (search for 'sendUntypedMsgToProtocol' method in UserAgent.scala to see these messages)
        //      (few others are like GetMsgs, UpdateMsgExpirationTime etc)

        val tm = typedMsg[Any](m)
        sendTypedMsgToProtocol(tm, relationshipId, DEFAULT_THREAD_ID, UNKNOWN_SENDER_PARTICIPANT_ID,
          Option(MsgRespConfig(isSyncReq(m))), None, None)
      } catch protoExceptionHandler
  }

  def agentCommonEventReceiver: Receive = {
    //NOTE: ProtocolIdDetailSet is a proto buf event which stores mapping between protocol reference and corresponding protocol identifier
    //There is a method 'getPinstId' below, which uses that stored state/mapping to know if a protocol actor (for given protocol reference),
    // is already created in the given context(like agency agent pairwise or user agent pairwise actor etc),
    // and if it is, then it uses that identifier to send incoming message to the protocol actor, or else creates a new protocol actor.
    case ProtocolIdDetailSet(msgFamilyName, msgFamilyVersion, pinstId) =>
      addPinst(ProtoRef(msgFamilyName, msgFamilyVersion) -> pinstId)

    case tcs: ThreadContextStored =>
      val msgTypeFormat = try {
        TypeFormat.fromString(tcs.msgTypeDeclarationFormat)
      } catch {
        //This is for backward compatibility (for older events which doesn't have msgTypeFormatVersion stored)
        case _: UnknownFormatType =>
          TypeFormat.fromString(tcs.msgPackFormat)
      }

      val tcd = ThreadContextDetail(tcs.threadId, MsgPackFormat.fromString(tcs.msgPackFormat), msgTypeFormat,
        tcs.usesGenMsgWrapper, tcs.usesBundledMsgWrapper)

      addThreadContextDetail(tcs.pinstId, tcd)

    case _: FirstProtoMsgSent => //nothing to do (deprecated, just kept it for backward compatibility)

    case pms: ProtoMsgSenderOrderIncremented =>
      val stc = state.threadContextDetailReq(pms.pinstId)
      val protoMsgOrderDetail = stc.msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val updatedProtoMsgOrderDetail =
        protoMsgOrderDetail.copy(senderOrder = protoMsgOrderDetail.senderOrder + 1)
      val updatedContext = stc.copy(msgOrders = Option(updatedProtoMsgOrderDetail))
      addThreadContextDetail(pms.pinstId, updatedContext)

    case pms: ProtoMsgReceivedOrderIncremented  =>
      val stc = state.threadContextDetailReq(pms.pinstId)
      val protoMsgOrderDetail = stc.msgOrders.getOrElse(MsgOrders(senderOrder = -1))
      val curReceivedMsgOrder = protoMsgOrderDetail.receivedOrders.getOrElse(pms.fromPartiId, -1)
      val updatedReceivedOrders = protoMsgOrderDetail.receivedOrders + (pms.fromPartiId -> (curReceivedMsgOrder + 1))
      val updatedProtoMsgOrderDetail =
        protoMsgOrderDetail.copy(receivedOrders = updatedReceivedOrders)
      val updatedContext = stc.copy(msgOrders = Option(updatedProtoMsgOrderDetail))
      addThreadContextDetail(pms.pinstId, updatedContext)

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
        }
        threadContextMigrationStatus += (tcmc.pinstId -> updatedStatus)
      }
  }

  def isMigrateThreadContextsEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.MIGRATE_THREAD_CONTEXTS_ENABLED)
      .getOrElse(false)

  def migrateThreadContexts(): Unit = {
    if (isMigrateThreadContextsEnabled) {
      val candidateThreadContexts =
        state.threadContext.map(_.contexts).getOrElse(Map.empty)
          .take(migrateThreadContextBatchSize)
      if (candidateThreadContexts.isEmpty) {
        stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
      } else {
        Future {
          candidateThreadContexts.foreach { case (pinstId, tcd) =>
            val candidateProtoActors = com.evernym.verity.protocol.protocols.protocolRegistry.entries.map { e =>
              val migrationStatus = threadContextMigrationStatus.get(pinstId)
              val s = migrationStatus.map(_.successResponseFromProtoActors).getOrElse(Set.empty)
              val ns = migrationStatus.map(_.nonSuccessResponseFromProtoActors).getOrElse(Set.empty)
              val respReceivedFromProtoRefs = s ++ ns
              if (! respReceivedFromProtoRefs.contains(e.protoDef.msgFamily.protoRef)) {
                val cmd = ForIdentifier(pinstId, SetThreadContext(tcd))
                val calcPinstId = e.pinstIdResol.resolve(e.protoDef, domainId, relationshipId, Option(tcd.threadId), None, contextualId)
                if (e.pinstIdResol == PinstIdResolution.DEPRECATED_V0_1 || pinstId == calcPinstId) {
                  e.protoDef -> Option(cmd)
                } else e.protoDef -> None
              } else e.protoDef -> None
            }.filter(_._2.isDefined)

            if (! threadContextMigrationStatus.contains(pinstId)) {
              if (candidateProtoActors.size <= 7) {
                logger.debug(s"[$persistenceId] all candidates are of pinst id resolution DEPRECATED_V01")
              } else {
                logger.debug(s"[$persistenceId] all candidates are of pinst id resolution V02")
              }
              val event = ThreadContextMigrationStarted(pinstId, candidateProtoActors.map(_._1.msgFamily.protoRef.toString))
              applyEvent(event)
              writeWithoutApply(event)
            }

            candidateProtoActors.foreach { case (pd, cmdOpt) =>
              ActorProtocol(pd).region.tell(cmdOpt.get, self)
              java.lang.Thread.sleep(migrateThreadContextBatchItemSleepInterval)
            }

          }
        }
      }
    } else {
      stopScheduledJob(MIGRATE_SCHEDULED_JOB_ID)
    }
  }

  type ResponseReceived = Boolean
  var threadContextMigrationStatus: Map[PinstId, ThreadContextMigrationStatus] = Map.empty
  /**
   * in memory state, stores information required to send response
   * to a synchronous requests
   */
  var msgRespContext: Map[MsgId, MsgRespContext] = Map.empty

  /**
   * This was used in the first version of pinstId resolution.
   * The value agentPairwiseDID is contextual, depending on implementing class,
   * and that context was baked into the pinstId hash.
   * We will need this until we can remove the V0_1 pinstId resolver.
   */
  lazy override val contextualId: Option[String] = state.thisAgentKeyDID

  def senderParticipantId(senderVerKey: Option[VerKey]): ParticipantId
  def selfParticipantId: ParticipantId

  /**
   * key info belonging to "this" agent (edge/cloud)
   * @return
   */
  lazy val thisAgentKeyInfo: KeyInfo = KeyInfo(Left(state.thisAgentVerKeyReq))
  lazy val msgExtractor: MsgExtractor = new MsgExtractor(thisAgentKeyInfo, agentActorContext.walletAPI)

  def getNewMsgId: MsgId = MsgIdProvider.getNewMsgId

  //TODO: if not overridden, it is using 'senderVerKey'
  // but idea was to use a DID, so we should come back to this function and re-assess it
  def userDIDForResourceUsageTracking(senderVerKey: Option[VerKey]): Option[DID] = senderVerKey

  def receiveAgentEvent: Receive
  def receiveAgentCmd: Receive

  def relationshipId: Option[RelationshipId] = state.myDid
  override def trackingTheirRelationshipId: Option[RelationshipId] = state.theirDid

  //tracing/tracking related
  override def trackingDomainId: Option[String] = Option(domainId)
  override def trackingMyRelationshipId: Option[String] = relationshipId

  //NOTE: this tells if this actor is ready to handle incoming messages or not
  //this was only required so that agency agent doesn't start unpacking messages
  //before it's setup process is completed (meaning agency agent key is created and its endpoint is written to the ledger)
  def isReadyToHandleIncomingMsg: Boolean = true

  override final def receiveEvent: Receive = agentCommonEventReceiver orElse receiveAgentEvent

  def isSyncReq(msg: Any): Boolean = {
    msg match {
      case _: GetInviteDetail => true
      case _ => false
    }
  }

  def fixActorState(did: DID): Unit = {
    sender ! InitialActorState(did, state.threadContext.map(_.contexts.size).getOrElse(0))
    state.myDid.map(myDID => setRoute(myDID))
    checkActorStateCleanupState(did)
  }

  def checkActorStateCleanupState(actorDID: DID): Unit = {
    val sndr = sender()
    val allThreadContextMigrated = state.threadContext.forall(tc => tc.contexts.isEmpty)
    state.myDid match {
      case Some(did) if did != actorDID =>
        getRoute(did).mapTo[Option[ActorAddressDetail]].map { r =>
          sndr ! ActorStateCleanupStatus(
            actorDID,
            isRouteFixed = r.isDefined,
            isAllThreadContextMigrated = allThreadContextMigrated,
            threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size == 1),
            threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size != 1)
          )
        }
      case _ =>
        sndr ! ActorStateCleanupStatus(
          actorDID,
          isRouteFixed = true,
          isAllThreadContextMigrated = allThreadContextMigrated,
          threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size == 1),
          threadContextMigrationStatus.count(_._2.successResponseFromProtoActors.size != 1))
    }
  }

  lazy val walletSeed: String = agentWalletSeedReq

  def storeOutgoingMsg(omp: OutgoingMsgParam, msgId:MsgId, msgName: MsgName,
                       senderDID: DID, threadOpt: Option[Thread]): Unit = {
    Future.successful("default implementation of storeOutgoingMsg")
  }

  def sendStoredMsgToEdge(msgId:MsgId): Future[Any] = {
    // flow diagram: fwd.edge, step 10 -- Queue msg for delivery to edge.
    Future.successful("default implementation of sendStoredMsgToEdge")
  }

  def sendMsgToOtherEntity(omp: OutgoingMsgParam, msgId: MsgId, msgName: MsgName, thread: Option[Thread]=None): Future[Any] = {
    Future.successful("default implementation of sendPackedMsgToOtherEntity")
  }

  def sendUnstoredMsgToEdge(omp: OutgoingMsgParam): Future[Any] = {
    Future.successful("default implementation of sendUnstoredMsgToEdge")
  }

  override def getPinstId(protoDef: ProtoDef): Option[PinstId] = state.getPinstId(protoDef)

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

  scheduleJob(
    MIGRATE_SCHEDULED_JOB_ID,
    migrateThreadContextScheduledJobInitialDelay,
    migrateThreadContextScheduledJobInterval,
    MigrateThreadContexts)
}

/**
 * this is used during incoming message processing to specify request/response context information
 *
 * @param isSyncReq determines if the incoming request expects a synchronous response
 * @param packForVerKey determines if the outgoing/signal messages should be packed with this ver key instead
 */
case class MsgRespConfig(isSyncReq:Boolean, packForVerKey: Option[VerKey]=None)

/**
 * used to store information related to incoming msg which will be used during outgoing/signal message processing
 * @param senderPartiId sender participant id
 * @param packForVerKey special ver key to be used to pack outgoing/signal message (so far this is only used for
 *                      'wallet backup restore' message
 * @param senderActorRef actor reference (of waiting http connection) to which the response needs to be sent
 */
case class MsgRespContext(senderPartiId: ParticipantId, packForVerKey: Option[VerKey]=None, senderActorRef:Option[ActorRef]=None)

case object MigrateThreadContexts extends ActorMessageObject

case class FixActorState(actorID: DID) extends ActorMessageClass
case class CheckActorStateCleanupState(actorID: DID) extends ActorMessageClass
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