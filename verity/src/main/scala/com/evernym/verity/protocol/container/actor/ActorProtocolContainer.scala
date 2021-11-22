package com.evernym.verity.protocol.container.actor

import akka.actor.ActorRef
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.relationship.RelationshipLike
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.user.{ComMethodDetail, GetSponsorRel}
import com.evernym.verity.actor.agent.{AgentActorContext, AgentIdentity, HasWallet, ProtocolEngineExceptionHandler, SponsorRel, ThreadContextDetail}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.observability.logs.LoggingUtil.getAgentIdentityLoggerByName
import com.evernym.verity.observability.metrics.CustomMetrics.AS_NEW_PROTOCOL_COUNT
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.msg.{SetDataRetentionPolicy, SetDomainId, SetSponsorRel, SetStorageId}
import com.evernym.verity.protocol.protocols.connecting.common.SmsTools
import com.evernym.verity.protocol.{Control, CtlEnvelope}
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.util.Util
import com.evernym.verity.util2.{ActorResponse, Exceptions, ServiceEndpoint}
import com.typesafe.scalalogging.Logger

import java.util.UUID
import akka.util.Timeout
import com.evernym.verity.actor.agent.msghandler.outgoing.ProtocolSyncRespMsg
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.did.didcomm.v1.messages.{MsgId, MsgType, TypedMsgLike}
import com.evernym.verity.observability.logs.HasLogger
import com.evernym.verity.protocol.container.asyncapis.ledger.LedgerAccessAPI
import com.evernym.verity.protocol.container.asyncapis.segmentstorage.SegmentStoreAccessAPI
import com.evernym.verity.protocol.container.asyncapis.urlshortener.UrlShorteningAPI
import com.evernym.verity.protocol.container.asyncapis.wallet.WalletAccessAPI
import com.evernym.verity.protocol.engine.asyncapi.AsyncOpRunner
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccessAdapter
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.SegmentStoreAccessController
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccessController
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccessAdapter
import com.evernym.verity.protocol.engine.container.{ProtocolContainer, RecordsEvents}
import com.evernym.verity.protocol.engine.events.PairwiseRelIdsChanged
import com.evernym.verity.util2.Exceptions.BadRequestErrorException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
/**
 *
 * @tparam P Protocol type
 * @tparam R Role type
 * @tparam M Message type
 * @tparam E Event type
 * @tparam S State type
 * @tparam I Message Recipient Identifier Type
 */

class ActorProtocolContainer[
  P <: Protocol[P,R,M,E,S,I],
  PD <: ProtocolDefinition[P,R,M,E,S,I],
  R,M,E <: Any,
  S,
  I]
(
  val agentActorContext: AgentActorContext,
  val definition: PD,
  val executionContext: ExecutionContext
)
  extends ProtocolContainer[P,R,M,E,S,I]
    with HasLegacyProtocolContainerServices[M,E,I]
    with BasePersistentActor
    with DefaultPersistenceEncryption
    with ProtocolEngineExceptionHandler
    with AgentIdentity
    with HasWallet
    with ProtocolSnapshotter[S]
    with HasLogger {

  implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  override final val receiveEvent: Receive = {
    case evt: Any => applyRecordedEvent(evt)
  }
  override final def receiveCmd: Receive = initialBehavior

  override lazy val logger: Logger = getAgentIdentityLoggerByName(
    this,
    s"${getProtoRef.toString}"
  )(context.system)

  override val appConfig: AppConfig = agentActorContext.appConfig

  override def serviceKeyDidFormat: Boolean = appConfig.getBooleanReq(SERVICE_KEY_DID_FORMAT)
  lazy val pinstId: PinstId = entityId
  var senderActorRef: Option[ActorRef] = None
  var agentWalletId: Option[String] = None

  override def domainId: DomainId = backState.domainId.getOrElse(throw new RuntimeException("DomainId not available"))

  def toBaseBehavior(): Unit = {
    logger.debug("becoming baseBehavior")
    setNewReceiveBehaviour(baseBehavior)
    unstashAll()
  }

  // This function is only called when the actor is uninitialized; later,
  // the receiver becomes inert.
  final def initialBehavior: Receive = {
    case ProtocolCmd(InitProtocol(domainId, parameters, sponsorRelOpt), None)=>
      submit(SetDomainId(domainId))
      submit(SetStorageId(pinstId))
      submit(SetDataRetentionPolicy(parameters.find(_.name == DATA_RETENTION_POLICY).map(_.value)))

      if(parameters.nonEmpty) {
        logger.debug(s"$protocolIdForLog about to send init msg")
        submitInitMsg(parameters)
        logger.debug(s"$protocolIdForLog protocol instance initialized successfully")
      }
      toBaseBehavior()
      // Ask for sponsor details from domain and record metric for initialized protocol
      sponsorRelOpt match {
        case Some(sr) => handleSponsorRel(sr)
        case None     => agentActorContext.agentMsgRouter.forward(InternalMsgRouteParam(domainId, GetSponsorRel), self)
      }
    case ProtocolCmd(FromProtocol(fromPinstId, newRel), _) =>
      logger.info(s"[$pinstId] protocol event migration started (fromPinstId: '$fromPinstId')")
      newRel.relationshipType match {
        case PAIRWISE_RELATIONSHIP =>
          val changeRelEvt = PairwiseRelIdsChanged(newRel.myDid_!, newRel.theirDid_!)
          toCopyEventsBehavior(changeRelEvt)
          context.system.actorOf(
            ExtractEventsActor.prop(
              appConfig,
              entityType,
              fromPinstId,
              self,
              executionContext
            ),
            s"ExtractEventsActor-${UUID.randomUUID().toString}"
          )
        case _ =>
          logger.warn(s"Command to Move protocol (fromPinstId: $fromPinstId) to a NON-PAIRWISE relationship")
      }

    case ProtocolCmd(stc: SetThreadContext, None) => handleSetThreadContext(stc.tcd)
    case ProtocolCmd(_, metadata) =>
      logger.debug(s"$protocolIdForLog protocol instance created for first time")
      stash()
      metadata.foreach { m =>
        setForwarderParams(m.walletId, m.forwarder)
      }
      recoverOrInit()
  }

  final def baseBehavior: Receive = {
    case s: SponsorRel                             => handleSponsorRel(s)
    case ProtocolCmd(stc: SetThreadContext, None)  => handleSetThreadContext(stc.tcd)
    case ProtocolCmd(stc: FromProtocol, None)      => logger.info(s"[$pinstId] protocol events already migrated from '${stc.fromPinstId}'")
    case pc: ProtocolCmd                           => handleProtocolCmd(pc)
  }

  def toCopyEventsBehavior(changeRelEvt: Any): Unit = {
    logger.debug("becoming copyEventsBehavior")
    setNewReceiveBehaviour(copyEventsBehavior(changeRelEvt))
  }

  final def copyEventsBehavior(changeRelEvt: Any): Receive = {
    case ProtocolCmd(ExtractedEvent(event), None) =>
      persistExt(event)( _ => applyRecordedEvent(event) )
    case ProtocolCmd(ExtractionComplete(), None) =>
      logger.info(s"[$pinstId] protocol event migration completed")
      persistExt(changeRelEvt){ _ =>
        applyRecordedEvent(changeRelEvt)
        toBaseBehavior()
      }
    case msg: Any =>
      logger.debug(s"$protocolIdForLog received msg: $msg while copy events")
      stash()
  }

  override def postSuccessfulActorRecovery(): Unit = {
    if (!state.equals(definition.initialState)){
      toBaseBehavior()
    }
  }

  def handleProtocolCmd(cmd: ProtocolCmd): Unit = {
    logger.debug(s"$protocolIdForLog handling ProtocolCmd: " + cmd)

    cmd.metadata.foreach { m =>
      storePackagingDetail(m.threadContextDetail)
      setForwarderParams(m.walletId, m.forwarder)
    }

    if(sender() != self) {
      senderActorRef = Option(sender())
    }

    val (msgId, msgToBeSent) = cmd.msg match {
      case c: Control =>
        val newMsgId = MsgFamilyUtil.getNewMsgUniqueId
        (newMsgId, CtlEnvelope(c, newMsgId, DEFAULT_THREAD_ID))
      case MsgEnvelope(msg: Control, _, _, _, Some(msgId), Some(thId))  =>
        (msgId, CtlEnvelope(msg, msgId, thId))
      case MsgEnvelope(msg: Any, _, to, frm, Some(msgId), Some(thId))   =>
        (msgId, Envelope1(msg, to, frm, Some(msgId), Some(thId)))
    }
    submit(msgToBeSent, Option(handleResponse(_, Some(msgId), senderActorRef)))
  }

  def handleSponsorRel(s: SponsorRel): Unit = {
    if (!s.equals(SponsorRel.empty)) submit(SetSponsorRel(s.sponsorId, s.sponseeId))
    val tags = ConfigUtil.getSponsorRelTag(appConfig, s) ++ Map("proto-ref" -> getProtoRef.toString)
    metricsWriter.gaugeIncrement(AS_NEW_PROTOCOL_COUNT, tags = tags)
  }

  /**
   * handles thread context migration
   * @param tcd thread context detail
   */
  def handleSetThreadContext(tcd: ThreadContextDetail): Unit = {
    if (! state.equals(definition.initialState)) {
      storePackagingDetail(tcd)
      sender ! ThreadContextStoredInProtoActor(pinstId, getProtoRef)
    } else {
      sender ! ThreadContextNotStoredInProtoActor(pinstId, getProtoRef)
    }
  }

  def setForwarderParams(_walletSeed: String, forwarder: ActorRef): Unit = {
    msgForwarder.setForwarder(forwarder)
    agentWalletId = Option(_walletSeed)
  }

  val eventRecorder: RecordsEvents = new RecordsEvents {
    //NOTE: as of now, don't see any other way to get around this except setting it as an empty vector.
    def recoverState(pinstId: PinstId): (_, Vector[_]) = {
      (state, Vector.empty)
    }

    def record(pinstId: PinstId, event: Any, state: Any)(cb: Any => Unit): Unit = persistExt(event)(cb)
  }

  def requestInit(): Unit = {
    logger.debug(s"$protocolIdForLog about to send InitProtocolReq to forwarder: ${msgForwarder.forwarder}")
    val forwarder = msgForwarder.forwarder.getOrElse(throw new RuntimeException("forwarder not set"))

    forwarder ! InitProtocolReq(definition.initParamNames, getProtoRef)
  }

  lazy val driver: Option[Driver] = {
    val parameter = ActorDriverGenParam(context.system, appConfig, agentActorContext.protocolRegistry,
      agentActorContext.generalCache, agentActorContext.agentMsgRouter, msgForwarder)
    agentActorContext.protocolRegistry.generateDriver(definition, parameter, futureExecutionContext)
  }

  val sendsMsgs = new MsgSender

  //TODO move agentActorContext.smsSvc to be handled here (uses a future)
  class MsgSender extends SendsMsgsForContainer[M](this) {

    def send(pom: ProtocolOutgoingMsg): Unit = {
      //because the 'agent msg processor' actor contains the response context
      // this message needs to go back to the same 'agent msg processor' actor
      // from where it was came earlier to this actor
      msgForwarder.forwarder.foreach(_ ! pom)
    }

    //dhh It surprises me to see this feature exposed here. I would have expected it
    // to be encapsulated elsewhere.
    def sendSMS(toPhoneNumber: String, msg: String): Future[String] = {
      val smsInfo: SmsInfo = SmsInfo(toPhoneNumber, msg)
      SmsTools.sendTextToPhoneNumber(
        smsInfo
      )(
        agentActorContext.appConfig,
        agentActorContext.smsSvc,
        agentActorContext.msgSendingSvc,
        futureExecutionContext
      )
    }
  }

  final override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    eventPersistenceFailure(cause, event)
    super.onPersistFailure(cause, event, seqNr)
  }

  final override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    eventPersistenceFailure(cause, event)
    super.onPersistRejected(cause, event, seqNr)
  }

  lazy val msgForwarder = new MsgForwarder
  override val defaultReceiveTimeoutInSeconds: Int = 900
  override val entityCategory: String = PERSISTENT_PROTOCOL_CONTAINER

  override def serviceEndpoint: ServiceEndpoint = {
    Util.buildAgencyEndpoint(appConfig).url
  }


  //---------------- async api infrastructure code

  /**
   * async api context needed by async api implementation for different purposes:
   *  a. most of them needs asyncOpRunner to be able to successfully process/execute async operation
   *  b. if async operation uses 'tell' to send command to another actor, it needs to know the sender (self in this case)
   *     to be able to send back response to this actor (protocol container actor)
   *  c. if async operation uses 'ask' pattern, it needs to know timeout
   * @return
   */
  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(appConfig, self, context, responseTimeout)
  implicit def asyncOpRunner: AsyncOpRunner = this


  override lazy val wallet =
    new WalletAccessAdapter(
      new WalletAccessAPI(
        agentActorContext.walletAPI,
        getRoster.selfId_!)
    )

  override lazy val ledger =
    new LedgerAccessAdapter(
      null, //TODO: replace this with actual VDR Adapter implementation
        agentActorContext.generalCache,
        agentActorContext.ledgerSvc,
        wallet
    )

  override lazy val urlShortening =
    new UrlShorteningAccessController(
      grantedAccessRights,
      new UrlShorteningAPI(executionContext)
    )

  override lazy val segmentStore =
    new SegmentStoreAccessController(
      new SegmentStoreAccessAPI(
        agentActorContext.storageAPI,
        getProtoRef
      )
    )

  /**
   * receive behaviour when async operation is in progress
   * it should only entertain async operation responses and stash anything else.
   * @return
   */
  def toAsyncOpInProgressBehaviour: Receive = {

    //TODO: what else should be stashed? Is this approach good enough?
    case _: ProtocolCmd | _: SponsorRel => stash()

    //async future responses
    case AsyncOpResp(asyncOpResult)    =>
      postAsyncOpResult(asyncOpResult)
    //non future based async op responses (from actors)
    case asyncOpResult                 => postAsyncOpResult(Try(asyncOpResult))
  }

  private def postAsyncOpResult(resp: Try[Any]): Unit = {
    //NOTE: this (context.unbecome()) will only work correctly if "discardOld" was set to false
    // when last time receiver was changed
    context.unbecome()
    unstashAll()

    withHandleResp { executeCallbackHandler(resp) }
  }

  /**
   * run the supplied operation asynchronously and handle below mentioned aspects:
   *   a. before running given operation, switch to a new behaviour which will stash any commands
   *      except those related to the async operation
   *   b. run the async operation.
   *   c. in case async operation returns Future, then send back the response to self
   *      to be processed by main thread.
   * @param op
   */
  override protected def runAsyncOp(op: => Any): Unit = {
    //NOTE: using "discardOld" as false, so it will add this new behaviour to the "behavior stack"
    setNewReceiveBehaviour(toAsyncOpInProgressBehaviour, discardOld = false)
    withHandleResp {
      val result = op //given operation gets executed here
      val sndr = sender()
      result match {
        //mostly this should be if async operation sent a command to an actor via tell
        case () =>

        //there are still few apis which responds with Future (ultimately they should migrated to use actor tell)
        case f: Future[Any] =>
          f.recover {
            case e: Exception =>
              abortTransaction(); throw e
          }.onComplete { resp =>
            self.tell(AsyncOpResp(resp), sndr) //keep the original sender
          }

        case other =>
          abortTransaction(); throw new RuntimeException("unexpected response while executing async operation: " + other)
      }
    }
  }

  /**
   * If we want to execute Future without a temporal actor, we can't use runAsyncOp method for this purpose
   * It's because for Future(Failure(..)) case all saved callbacks will be cleaned via abortTransaction()
   * But Failure case can be expected
   */
  override protected def runFutureAsyncOp(op: => Future[Any]): Unit = {
    setNewReceiveBehaviour(toAsyncOpInProgressBehaviour, discardOld = false)
    withHandleResp {
      val result = op //given operation gets executed here
      val sndr = sender()
      result.onComplete {
        r => self.tell(AsyncOpResp(r), sndr)
      }
    }
  }

  def withHandleResp(code: => Unit): Unit = {
    //NOTE: using 'handleResponse' in below line to be able to send back synchronous response
    // of callback handler execution to waiting caller in case it original request was expecting a synchronous response
    val msgId = Try(getInFlight.msgId).getOrElse(None)
    handleResponse(Try(code), msgId, senderActorRef)
  }

  /**
   * this is for legacy protocol and for unhandled error while executing async apis
   * @param resp
   * @param msgIdOpt
   * @param sndr
   */
  def handleResponse(resp: Try[Any], msgIdOpt: Option[MsgId], sndr: Option[ActorRef]): Unit = {
    def sendRespToCaller(resp: Any, msgIdOpt: Option[MsgId], sndr: Option[ActorRef]): Unit = {
      sndr.filter(_ != self).foreach { ar =>
        ar ! ProtocolSyncRespMsg(ActorResponse(resp), msgIdOpt)
      }
    }

    resp match {
      case Success(r)  =>
        r match {
          case ()               => //if unit then nothing to do
          case fut: Future[Any] => handleFuture(fut, msgIdOpt)
          case x                => sendRespToCaller(x, msgIdOpt, sndr)
        }

      case Failure(e) =>
        e match {
          case bre: BadRequestErrorException =>
            logger.info("bad request response from protocol actor: " + bre.getMessage)
          case other =>
            logger.error("error response from protocol actor: " + Exceptions.getStackTraceAsSingleLineString(other))
        }
        val error = convertProtoEngineException(e)
        sendRespToCaller(error, msgIdOpt, sndr)
    }

    def handleFuture(fut: Future[Any], msgIdOpt: Option[MsgId]): Unit = {
      fut.map {
        case Right(r) => sendRespToCaller(r, msgIdOpt, sndr)
        case Left(l)  => sendRespToCaller(l, msgIdOpt, sndr)
        case x        => sendRespToCaller(x, msgIdOpt, sndr)
      }.recover {
        case e: Exception =>
          sendRespToCaller(e, msgIdOpt, sndr)
      }
    }
  }
}

//trait ProtoMsg extends MsgBase


/**
 * This is sent by LaunchesProtocol during protocol initialization process.
 * Protocol actor (via protocol state in it) knows if it has been already initialized or not.
 * If it is not initialized, then the protocol actor will stash incoming commands and
 * send 'InitProtocolReq' back to those message senders.
 * And the protocol message forwarder (like UserAgentPairwise) is supposed to handle
 * that 'InitProtocol' command and respond with
 *
 * @param domainId domain id
 * @param parameters protocol initialization parameters
 */
case class InitProtocol(domainId: DomainId,
                        parameters: Set[Parameter],
                        sponsorRel: Option[SponsorRel]) extends ActorMessage

/**
 * This is used by this actor during protocol initialization process.
 * It is sent to the message forwarder (which is available in ProtocolCmd)
 * @param stateKeys - set of keys/names whose value is needed by the protocol.
 */
case class InitProtocolReq(stateKeys: Set[String], protoRef: ProtoRef) extends ActorMessage

case class ProtocolCmd(msg: Any, metadata: Option[ProtocolMetadata]) extends ActorMessage

/*
  walletSeed: actor protocol container needs to access/provide wallet service
  and for that it needs this walletSeed

  forwarder: actor reference for the launcher (who forwards the incoming msg to the actor protocol container)
  which is then provided into driver and driver uses it to reach to the same agent (launcher)
  who originally forwarded the msg
 */
case class ProtocolMetadata(forwarder: ActorRef,
                            walletId: String,
                            threadContextDetail: ThreadContextDetail)
/**
 * incoming msg envelope
 * @param msg
 * @param msgType
 * @param to
 * @param frm
 * @param msgId
 * @param thId
 */
case class MsgEnvelope(msg: Any,
                       msgType: MsgType,
                       to: ParticipantId,
                       frm: ParticipantId,
                       msgId: Option[MsgId]=None,
                       thId: Option[ThreadId]=None) extends TypedMsgLike with ActorMessage {
  def typedMsg: TypedMsg = TypedMsg(msg, msgType)
}

trait ServiceDecorator{
  def msg: Any
  def deliveryMethod: ComMethodDetail
}

class MsgForwarder {
  private var _forwarder: Option[ActorRef] = None
  def setForwarder(actorRef: ActorRef): Unit = _forwarder = Option(actorRef)
  def forwarder:Option[ActorRef] = _forwarder
}

case class SetThreadContext(tcd: ThreadContextDetail) extends ActorMessage

case class ThreadContextStoredInProtoActor(pinstId: PinstId, protoRef: ProtoRef) extends ActorMessage
case class ThreadContextNotStoredInProtoActor(pinstId: PinstId, protoRef: ProtoRef) extends ActorMessage

case class FromProtocol(fromPinstId: PinstId, newRelationship: RelationshipLike)


/**
 * wrapping future's responses to a case class as otherwise those responses (for example Tuple etc)
 * all will have to extend ActorMessage
 */
case class AsyncOpResp(resp: Try[Any]) extends ActorMessage

case class AsyncAPIContext(appConfig: AppConfig,
                           senderActorRef: ActorRef,
                           senderActorContext: akka.actor.ActorContext,
                           timeout: Timeout = Timeout(50.seconds))
