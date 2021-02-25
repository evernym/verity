package com.evernym.verity.protocol.actor

import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.agent.relationship.RelationshipLike
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.user.{ComMethodDetail, GetSponsorRel}
import com.evernym.verity.actor.agent.{SponsorRel, _}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.segmentedstates.{GetSegmentedState, SaveSegmentedState, SegmentedStateStore, ValidationError}
import com.evernym.verity.actor.{StorageInfo, StorageReferenceStored, _}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.libindy.ledger.LedgerAccessApi
import com.evernym.verity.libindy.wallet.WalletAccessAPI
import com.evernym.verity.logging.LoggingUtil.getAgentIdentityLoggerByName
import com.evernym.verity.metrics.CustomMetrics.AS_NEW_PROTOCOL_COUNT
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncProtocol.{AsyncProtocolService, SegmentStateStoreProgress, UrlShorteningProgress, WalletProgress}
import com.evernym.verity.protocol.engine.external_api_access.{LedgerAccessController, WalletAccessController}
import com.evernym.verity.protocol.engine.msg.{GivenDomainId, GivenSponsorRel}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine.segmentedstate.{SegmentStoreStrategy, SegmentedStateMsg}
import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, UrlShorteningAccess, UrlShorteningAccessController}
import com.evernym.verity.protocol.protocols.connecting.common.SmsTools
import com.evernym.verity.protocol.protocols.HasWallet
import com.evernym.verity.protocol.{ChangePairwiseRelIds, Control, CtlEnvelope}
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.Util
import com.evernym.verity.ServiceEndpoint
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage
import java.util.UUID

import scala.concurrent.Future
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
  val segmentStoreStrategy: Option[SegmentStoreStrategy]
)
  extends ProtocolContainer[P,R,M,E,S,I]
    with HasLegacyProtocolContainerServices[M,E,I]
    with BasePersistentActor
    with DefaultPersistenceEncryption
    with ProtocolEngineExceptionHandler
    with AgentIdentity
    with HasWallet
    with HasLogger {

  override final val receiveEvent: Receive = {
    case evt: Any => applyRecordedEvent(evt)
  }
  override final def receiveCmd: Receive = initialBehavior

  override lazy val logger: Logger = getAgentIdentityLoggerByName(
    this,
    s"${definition.msgFamily.protoRef.toString}"
  )(context.system)

  override val appConfig: AppConfig = agentActorContext.appConfig
  lazy val pinstId: PinstId = entityId
  var senderActorRef: Option[ActorRef] = None
  var agentWalletId: Option[String] = None
  def sponsorRel: Option[SponsorRel] = backState.sponsorRel

  //def walletAPI: WalletAPI = agentActorContext.walletAPI
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
      submit(GivenDomainId(domainId))
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
      newRel.relationshipType match {
        case PAIRWISE_RELATIONSHIP =>
          val changeRelEvt = ChangePairwiseRelIds(newRel.myDid_!, newRel.theirDid_!)
          toCopyEventsBehavior(changeRelEvt)
          context.system.actorOf(
            ExtractEventsActor.prop(
              appConfig,
              entityType,
              fromPinstId,
              self
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
    case ProtocolCmd(stc: SetThreadContext, None)  => handleSetThreadContext(stc.tcd)
    case s: SponsorRel                             => handleSponsorRel(s)
    case pc: ProtocolCmd                           => handleProtocolCmd(pc)
  }

  /**
   * Becomes asyncProtocolBehavior.
   * Read asyncProtocolBehavior documentation.
   */
  def toProtocolAsyncBehavior(s: AsyncProtocolService): Unit = {
    logger.debug("becoming toProtocolAsyncBehavior")
    addsAsyncProtocolService(s)
    setNewReceiveBehaviour(asyncProtocolBehavior)
  }

  /**
   * When a protocol needs some asynchronous behavior done and the finalization of the state needs to wait until completion,
   * the 'Receive' method is transitioned to asyncProtocolBehavior.
   * This behavior handles things like the url-shortener, segmented state storage, wallet and ledger access.
   * The behavior is not transitioned back to the base behavior until all services have completed.
   */
  final def asyncProtocolBehavior: Receive = storingBehavior orElse asyncProtocolServiceBehavior orElse stashProtocolAsyncBehavior

  /**
   * This Receive is chained off asyncProtocolBehavior.
   * handles url-shortener and eventually wallet and ledger access.
   */
  final def asyncProtocolServiceBehavior: Receive = {
    //TODO: This is where WalletServiceComplete and LedgerServiceComplete will happen
    case ProtocolCmd(_: UrlShortenerServiceComplete, _) =>
      logger.debug(s"$protocolIdForLog received UrlShortenerServiceComplete")
      removesAsyncProtocolService(UrlShorteningProgress)
      if(asyncProtocolServicesComplete()) toBaseBehavior()
      handleAllAsyncServices()
    case ProtocolCmd(_: WalletServiceComplete, _) =>
      logger.debug(s"$protocolIdForLog received WalletServiceComplete")
      removesAsyncProtocolService(WalletProgress)
      if(asyncProtocolServicesComplete()) toBaseBehavior()
      handleAllAsyncServices()
  }

  /**
   * This Receive is chained off asyncProtocolBehavior.
   * When a protocol uses segmented state to store a segment or some type of storage, this happens asynchronously.
   * Incoming messages and protocol finalization should not happen until the completion of storing.
   */
  final def storingBehavior: Receive = {
    case ProtocolCmd(_: SegmentStorageComplete, _) =>
      logger.debug(s"$protocolIdForLog received StoreComplete")
      if(asyncProtocolServicesComplete()) toBaseBehavior()
    case ProtocolCmd(_: SegmentStorageFailed, _) =>
      logger.error(s"failed to store segment")
      if(asyncProtocolServicesComplete()) toBaseBehavior()
  }

  /**
   * This Receive is chained off asyncProtocolBehavior.
   * stashes any message received while a asyncProtocolBehavior type process is in progress.
   */
  final def stashProtocolAsyncBehavior: Receive = {
    case msg: Any => // we can't make a stronger assertion about type because erasure
      logger.debug(s"$protocolIdForLog received msg: $msg while handling async behavior in protocol - (segmented state, url-shortener, ledger, wallet")
      stash()
  }

  /**
   * Becomes retrievingBehavior.
   * Read retrievingBehavior documentation.
   */
  def toRetrievingBehavior(): Unit = {
    logger.debug("becoming retrievingBehavior")
    setNewReceiveBehaviour(retrievingBehavior)
  }

  /**
   * Behavior changes to handle messages regarding storage retrieval.
   * When a protocol stores state using the segmented state infrastructure, this happens asynchronously.
   * Incoming messages and protocol finalization should not happen until the completion of retrieving the storage.
   */
  final def retrievingBehavior: Receive = {
    case ProtocolCmd(_: DataRetrieved, _) =>
      logger.debug(s"$protocolIdForLog received DataRetrieved")
      toBaseBehavior()
    case ProtocolCmd(_: DataNotFound, _) =>
      logger.debug(s"$protocolIdForLog data not found")
      toBaseBehavior()
    case msg: Any => // we can't make a stronger assertion about type because erasure
      logger.debug(s"$protocolIdForLog received msg: $msg while retrieving data")
      stash()
  }

  def toCopyEventsBehavior(changeRelEvt: Any): Unit = {
    logger.debug("becoming copyEventsBehavior")
    setNewReceiveBehaviour(copyEventsBehavior(changeRelEvt))
  }

  final def copyEventsBehavior(changeRelEvt: Any): Receive = {
    case ProtocolCmd(ExtractedEvent(event), None) =>
      persistExt(event)( _ => applyRecordedEvent(event) )
    case ProtocolCmd(ExtractionComplete(), None) =>
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
      case m: MsgWithSegment =>
        (m.msgId, m)
    }
    submit(msgToBeSent, Option(handleResponse(_, Some(msgId), senderActorRef)))
  }

  def handleSponsorRel(s: SponsorRel): Unit = {
    if (!s.equals(SponsorRel.empty)) submit(GivenSponsorRel(s))
    val tags = ConfigUtil.getSponsorRelTag(appConfig, s) ++ Map("proto-ref" -> definition.msgFamily.protoRef.toString)
    MetricsWriter.gaugeApi.incrementWithTags(AS_NEW_PROTOCOL_COUNT, tags)
  }

  /**
   * handles thread context migration
   * @param tcd thread context detail
   */
  def handleSetThreadContext(tcd: ThreadContextDetail): Unit = {
    if (! state.equals(definition.initialState)) {
      storePackagingDetail(tcd)
      sender ! ThreadContextStoredInProtoActor(pinstId, definition.msgFamily.protoRef)
    } else {
      sender ! ThreadContextNotStoredInProtoActor(pinstId, definition.msgFamily.protoRef)
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

    def record(pinstId: PinstId, event: Any, state: Any, cb: Any => Unit): Unit = persistExt(event)(cb)
  }

  def requestInit(): Unit = {
    logger.debug(s"$protocolIdForLog about to send InitProtocolReq to forwarder: ${msgForwarder.forwarder}")
    val forwarder = msgForwarder.forwarder.getOrElse(throw new RuntimeException("forwarder not set"))

    forwarder ! InitProtocolReq(definition.initParamNames)
  }

  lazy val driver: Option[Driver] = {
    val parameter = ActorDriverGenParam(context.system, appConfig, agentActorContext.protocolRegistry,
      agentActorContext.generalCache, agentActorContext.agentMsgRouter, msgForwarder)
    agentActorContext.protocolRegistry.generateDriver(definition, parameter)
  }
  val sendsMsgs = new MsgSender

  //TODO move agentActorContext.smsSvc to be handled here (uses a future)
  class MsgSender extends SendsMsgsForContainer[M](this) {

    def send(pom: ProtocolOutgoingMsg): Unit = {
      //because the 'agent msg processor' actor contains the response context
      // this message needs to go back to the same 'agent msg processor' actor
      // from where it was came earlier to this actor
      //TODO-amp: shall we find better solution
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
        agentActorContext.msgSendingSvc)
    }
  }

  val storageService: StorageService = new StorageService {

    def read(id: ItemId, cb: Try[Array[Byte]] => Unit): Unit =
      agentActorContext.s3API download(id) onComplete cb

    def write(id: ItemId, data: Array[Byte], cb: Try[Any] => Unit): Unit =
      agentActorContext.s3API upload(id, data) onComplete cb
  }

  def handleSegmentedMsgs(msg: SegmentedStateMsg, postExecution: Either[Any, Option[Any]] => Unit): Unit = {
    def sendToSegmentedRegion(segmentAddress: SegmentAddress, cmd: Any): Unit = {
      val typeName = SegmentedStateStore.buildTypeName(definition.msgFamily.protoRef, definition.segmentedStateName.get)
      val segmentedStateRegion = ClusterSharding.get(context.system).shardRegion(typeName)
      val futResp = segmentedStateRegion ? ForIdentifier(segmentAddress, cmd)

      futResp.onComplete {
        case Success(s) => s match {
          case x: Option[Any] => postExecution(Right(x))
          case x: ValidationError => postExecution(Left(x))
        }
        case Failure(e) => postExecution(Left(e))
      }
    }

    def saveStorageState(segmentAddress: SegmentAddress, segmentKey: SegmentKey, data: GeneratedMessage): Unit = {
      toProtocolAsyncBehavior(SegmentStateStoreProgress)
      logger.debug(s"storing storage state: $data")
      storageService.write(segmentAddress + segmentKey, data.toByteArray, {
        case Success(storageInfo: StorageInfo) =>
          logger.debug(s"Data stored at: ${storageInfo.endpoint}")
          val storageReference = StorageReferenceStored(storageInfo.`type`, SegmentedStateStore.eventCode(data), Some(storageInfo))
          postExecution(Right(Some(Write(segmentAddress, segmentKey, storageReference))))
        case Success(value) =>
          // TODO the type constraint should make this case un-needed
          val msg = "storing information is not a excepted type, unable to process it " +
            s"-- it is ${value.getClass.getSimpleName}"
          logger.error(msg)
          postExecution(Left(new Exception(msg)))
        case Failure(e) =>
          logger.error(s"storing data externally failed with error: ${e.getMessage}")
          postExecution(Left(e))
      })
    }

    def saveSegmentedState(segmentAddress: SegmentAddress, segmentKey: SegmentKey, data: GeneratedMessage): Unit = {
      toProtocolAsyncBehavior(SegmentStateStoreProgress)
      data match {
        case segmentData if maxSegmentSize(segmentData) =>
          val cmd = SaveSegmentedState(segmentKey, segmentData)
          sendToSegmentedRegion(segmentAddress, cmd)
        case storageData =>
          logger.debug(s"storing $storageData in segment storage")
          handleSegmentedMsgs(WriteStorage(segmentAddress, segmentKey, storageData), postExecution)
      }
    }

    def readSegmentedState(segmentAddress: SegmentAddress, segmentKey: SegmentKey): Unit = {
      toRetrievingBehavior()
      val cmd = GetSegmentedState(segmentKey)
      sendToSegmentedRegion(segmentAddress, cmd)
    }

    def readStorageState(segmentAddress: SegmentAddress, segmentKey: SegmentKey, storageRef: StorageReferenceStored): Unit = {
      toRetrievingBehavior()
      storageService.read(segmentAddress + segmentKey, {
        case Success(data: Array[Byte]) =>
          val event = SegmentedStateStore buildEvent(storageRef.eventCode, data)
          postExecution(Right(Some(event)))
        case Failure(exception) =>
          postExecution(Left(Some(exception)))
      })
    }

    msg match {
      case Write(address: SegmentAddress, key: SegmentKey, data: GeneratedMessage) =>
        saveSegmentedState(address, key, data)
      case WriteStorage(segmentAddress: SegmentAddress, segmentKey: SegmentKey, value: GeneratedMessage) =>
        saveStorageState(segmentAddress, segmentKey, value)
      case Read(segmentAddress: SegmentAddress, segmentKey: SegmentKey) =>
        readSegmentedState(segmentAddress, segmentKey)
      case ReadStorage(segmentAddress: SegmentAddress, key: SegmentKey, ref: StorageReferenceStored) =>
        readStorageState(segmentAddress, key, ref)
    }
  }

  private lazy val walletAccessImpl = new WalletAccessAPI(
    agentActorContext.appConfig,
    agentActorContext.walletAPI,
    getRoster.selfId_!,
    {toProtocolAsyncBehavior(WalletProgress)},
    {addToMsgQueue(WalletServiceComplete())},
  )

  override lazy val wallet = new WalletAccessController(grantedAccessRights, walletAccessImpl)

  override lazy val ledger = new LedgerAccessController(
    grantedAccessRights,
    LedgerAccessApi(agentActorContext.generalCache, agentActorContext.ledgerSvc, wallet)
  )

  override lazy val urlShortening = new UrlShorteningAccessController(
    grantedAccessRights,
    urlShortener
  )

  private val urlShortener: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit = {
      logger.debug("in url shortening callback")
      toProtocolAsyncBehavior(UrlShorteningProgress)
      context.system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(inviteUrl) onComplete {
        case Success(m) =>
          m match {
            case UrlShortened(shortUrl) => handler(Success(InviteShortened(inviteUrl, shortUrl)))
            case UrlShorteningFailed(_, msg) => handler(Failure(new Exception(msg)))
          }
          addToMsgQueue(UrlShortenerServiceComplete())
        case Failure(e) =>
          handler(Failure(e))
          addToMsgQueue(UrlShortenerServiceComplete())
      }
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

}

trait ProtoMsg extends MsgBase

/**
 * This message is sent only when protocol is being created/initialized for first time
 * @param params - Set of Parameter (key & value) which protocol needs
 */

case class Init(params: Parameters) extends Control {
  def parametersStored: Set[ParameterStored] = params.initParams.map(p => ParameterStored(p.name, p.value))
}

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
case class InitProtocol(domainId: DomainId, parameters: Set[Parameter], sponsorRel: Option[SponsorRel]) extends ActorMessage

/**
 * This is used by this actor during protocol initialization process.
 * It is sent to the message forwarder (which is available in ProtocolCmd)
 * @param stateKeys - set of keys/names whose value is needed by the protocol.
 */
case class InitProtocolReq(stateKeys: Set[String]) extends ActorMessage

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
