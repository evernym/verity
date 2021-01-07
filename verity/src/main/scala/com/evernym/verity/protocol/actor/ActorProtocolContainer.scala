package com.evernym.verity.protocol.actor

import java.util.UUID
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.persistence.RecoveryCompleted
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.{AgentActorContext, AgentIdentity, ProtocolEngineExceptionHandler, SetupAgentEndpoint, SetupCreateKeyEndpoint, SponsorRel, ThreadContextDetail}
import com.evernym.verity.actor.agent.msghandler.outgoing.ProtocolSyncRespMsg
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.segmentedstates.{GetSegmentedState, SaveSegmentedState, SegmentedStateStore, ValidationError}
import com.evernym.verity.actor.{StorageInfo, StorageReferenceStored, _}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getAgentIdentityLoggerByName
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.msg.{GivenDomainId, GivenSponsorRel}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine.segmentedstate.{SegmentStoreStrategy, SegmentedStateMsg}
import com.evernym.verity.protocol.engine.util.getNewActorIdFromSeed
import com.evernym.verity.protocol.legacy.services._
import com.evernym.verity.protocol.protocols.{HasAgentWallet, HasAppConfig}
import com.evernym.verity.protocol.protocols.connecting.common.SmsTools
import com.evernym.verity.protocol.{Control, CtlEnvelope}
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.util.{ParticipantUtil, Util}
import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.ActorResponse
import com.evernym.verity.actor.agent.user.{ComMethodDetail, GetSponsorRel}
import com.evernym.verity.libindy.ledger.LedgerAccessApi
import com.evernym.verity.libindy.wallet.WalletAccessAPI
import com.evernym.verity.metrics.CustomMetrics.AS_NEW_PROTOCOL_COUNT
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.external_api_access.{LedgerAccessController, WalletAccessController}
import com.evernym.verity.protocol.engine.urlShortening.UrlShorteningAccess
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.github.ghik.silencer.silent
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

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
      with BasePersistentActor
      with DefaultPersistenceEncryption
      with HasLogger
      with TokenToActorMappingProvider
      with MsgQueueServiceProvider
      with CreateKeyEndpointServiceProvider
      with AgentEndpointServiceProvider
      with ProtocolEngineExceptionHandler
      with MsgTraceProvider
      with HasAgentWallet
      with HasAppConfig
      with AgentIdentity {


  override def domainId: DomainId = backstate.domainId.getOrElse(throw new RuntimeException("DomainId not available"))
  override lazy val logger: Logger = getAgentIdentityLoggerByName(this, s"${definition.msgFamily.protoRef.toString}")
  def sponsorRel: Option[SponsorRel] = backstate.sponsorRel

  override def receiveRecover: Receive = {
    /**
      * if protocol actor recovery is completed then the receiver should be set to 'protocolReceiveCommandBase' if any of this is true
      *   - it doesn't need any further initialization from container (definition.initParamNames.isEmpty)
      *   - state is not equal to initial state, which means, this actor has been already initialized
      */

    case RecoveryCompleted if ! state.equals(definition.initialState) =>
      changeReceiverToBase()
    case evt =>
      super.receiveRecover(evt)
  }

  def protocolReceiveEvent: Receive = {
    case evt: Any => // we can't make a stronger assertion about type because erasure

      //NOTE: assumption is that if any event is coming for recovery, then protocol should have
      // been already initialized [so that the last line of this code block 'protocol.apply(evt)' can work ]
      // if protocol is not yet initialized, it must be the case that this actor is just started and
      // is in event recovery process, so we should create the protocol instance.
      applyRecordedEvent(evt)
  }

  def protocolReceiveCommand: Receive = {
    case m @ (_:MsgWithSegment | _: Control | _:MsgEnvelope[_]) =>
      val (msgIdOpt, actualMsg, msgToBeSent) = m match {
        case c: Control =>
          // flow diagram: ctl.nothread, step 17 -- Control message without thread gets default.
          senderActorRef = Option(sender())
          (None, c, CtlEnvelope(c, MsgFamilyUtil.getNewMsgUniqueId, DEFAULT_THREAD_ID))

        // we can't make a stronger assertion about type because erasure
        case me @ MsgEnvelope(msg: Any, _, to, frm, Some(msgId), Some(thId))  =>
          senderActorRef = Option(sender())
          msg match {
            // flow diagram: ctl.thread, step 17 -- Handle control message with thread.
            case c: Control  => (me.msgId, c, CtlEnvelope(c, msgId, thId))
            // flow diagram: proto, step 17 -- Route message to appropriate protocol handler.
            case pm          => (me.msgId, pm, Envelope1(pm, to, frm, me.msgId, me.thId))
          }

          // flow diagram: ctl.rare, step 17
        case m: MsgWithSegment => (m.msgIdOpt, m, m)
      }
      val reqId = msgIdOpt.getOrElse(UUID.randomUUID().toString)

      MsgProgressTracker.recordProtoMsgStatus(definition, pinstId, "in-msg-process-started", reqId, inMsg = Option(actualMsg))
      submit(msgToBeSent, Option(handleResponse(_, msgIdOpt, senderActorRef)))
  }

  def handleResponse(resp: Try[Any], msgIdOpt: Option[MsgId], sndr: Option[ActorRef]): Unit = {
    resp match {
      case Success(r)  =>
        r match {
          case () => //if unit then nothing to do
          case fut: Future[Any] => handleFuture(fut, msgIdOpt)
          case x => sendRespToCaller(x, msgIdOpt, sndr)
        }

      case Failure(e) =>
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

  def sendRespToCaller(resp: Any, msgIdOpt: Option[MsgId], sndr: Option[ActorRef]): Unit = {
    sndr.foreach(_ ! ProtocolSyncRespMsg(ActorResponse(resp), msgIdOpt))
  }

  override val appConfig: AppConfig = agentActorContext.appConfig
  def walletAPI: WalletAPI = agentActorContext.walletAPI

  lazy val pinstId: PinstId = entityId

  override final val receiveEvent: Receive = protocolReceiveEvent

  override final def receiveCmd: Receive = uninitializedReceiveCommand

  var senderActorRef: Option[ActorRef] = None

  final def protocolReceiveCommandBase: Receive = {
    // flow diagram: ctl + proto, step 16
    //NOTE: this is when any actor/object wants to send a command with ProtocolCmd wrapper
    // we can't make a stronger assertion about type because erasure    
    case pc @ ProtocolCmd(MsgEnvelope(msg: Any, msgType, to, frm, msgId, thId), metadata) =>
      logger.debug(s"$protocolIdForLog received ProtocolCmd: " + pc)
      storePackagingDetail(metadata.threadContextDetail)
      setForwarderParams(metadata.walletId, metadata.forwarder)
      protocolReceiveCommand(MsgEnvelope(msg, msgType, to, frm, msgId, thId))

    // we can't make a stronger assertion about type because erasure
    case msg: Any if protocolReceiveCommand.isDefinedAt(msg) =>
      logger.debug(s"$protocolIdForLog received msg: " + msg)
      protocolReceiveCommand(msg)

    case stc: SetThreadContext => handleSetThreadContext(stc.tcd)

    case s: SponsorRel =>
      if (!s.equals(SponsorRel.empty)) submit(GivenSponsorRel(s))
      val tags = ConfigUtil.getSponsorRelTag(appConfig, s) ++ Map("proto-ref" -> definition.msgFamily.protoRef.toString)
      MetricsWriter.gaugeApi.incrementWithTags(AS_NEW_PROTOCOL_COUNT, tags)
  }

  def changeReceiverToBase(): Unit = {
    logger.debug("transitioning to protocolReceiveCommandBase")
    setNewReceiveBehaviour(protocolReceiveCommandBase)
    unstashAll()
  }

  // This function is only called when the actor is uninitialized; later,
  // the receiver becomes inert.
  final def uninitializedReceiveCommand: Receive = {
    case InitProtocol(domainId: DomainId, parameters: Set[Parameter]) =>
      MsgProgressTracker.recordProtoMsgStatus(definition, pinstId, "init-resp-received",
        "init-msg-id", inMsg = Option("init-param-received"))
      submit(GivenDomainId(domainId))
      if(parameters.nonEmpty) {
        logger.debug(s"$protocolIdForLog about to send init msg")
        sendInitMsgToProtocol(parameters)
        logger.debug(s"$protocolIdForLog protocol instance initialized successfully")
      }
      changeReceiverToBase()
      // Ask for sponsor details from domain and record metric for initialized protocol
      agentActorContext.agentMsgRouter.forward(InternalMsgRouteParam(domainId, GetSponsorRel), self)

    //TODO: we are purposefully expecting ProtocolCmd, so that sending actor can provide
    // its own reference and still keep the original sender un impacted
    // we should try to find a better way to handle it without ProtocolCmd
    case ProtocolCmd(_, metadata) =>
      logger.debug(s"$protocolIdForLog protocol instance created for first time")
      stash()
      setForwarderParams(metadata.walletId, metadata.forwarder)
      recoverOrInit()

    case stc: SetThreadContext => handleSetThreadContext(stc.tcd)
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

  def changeReceiverToDataStoring(): Unit = {
    logger.debug("becoming dataStoringReceive")
    setNewReceiveBehaviour(dataStoringReceive)
  }

  final def dataStoringReceive: Receive = {
    case _: SegmentStorageComplete =>
      logger.debug(s"$protocolIdForLog received StoreComplete")
      changeReceiverToBase()
    case _: SegmentStorageFailed =>
      logger.error(s"failed to store segment")
      changeReceiverToBase()
    case msg: Any => // we can't make a stronger assertion about type because erasure
      logger.debug(s"$protocolIdForLog received msg: $msg while storing data")
      stash()
  }

  //TODO -> RTM: Add documentation for this
  //dhh I'd like to understand the significance of changing receive behavior.
  // Is this part of the issue Jason wrote about with futures, where we are
  // going into different modes at different points in a sequence of actions
  // that contains multiple waits?
  def changeReceiverToRetrieve(): Unit = {
    logger.debug("becoming dataRetrievalReceive")
    setNewReceiveBehaviour(dataRetrievalReceive)
  }

  final def dataRetrievalReceive: Receive = {
    case _: DataRetrieved =>
      logger.debug(s"$protocolIdForLog received DataRetrieved")
      changeReceiverToBase()
    case _: DataNotFound =>
      logger.debug(s"$protocolIdForLog data not found")
      changeReceiverToBase()
    case msg: Any => // we can't make a stronger assertion about type because erasure
      logger.debug(s"$protocolIdForLog received msg: $msg while retrieving data")
      stash()
  }


  var agentWalletId: Option[String] = None

  def setForwarderParams(_walletSeed: String, fwder: ActorRef): Unit = {
    msgForwarder.setForwarder(fwder)
    agentWalletId = Option(_walletSeed)
  }

  override def createToken(uid: String): Future[Either[HandledErrorException, String]] = {
    agentActorContext.tokenToActorItemMapperProvider.createToken(entityName, entityId, uid)
  }

  def addToMsgQueue(msg: Any): Unit = {
    self ! msg
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

    MsgProgressTracker.recordProtoMsgStatus(definition, pinstId, "init-param-req-sent",
      "init-msg-id", outMsg = Option("init-req-sent"))
  }

  @silent
  override def createServices: Option[Services] = {

    Some(new LegacyProtocolServicesImpl[M,E,I](
      eventRecorder, sendsMsgs, agentActorContext.appConfig,
      agentActorContext.walletAPI, agentActorContext.generalCache,
      agentActorContext.remoteMsgSendingSvc, agentActorContext.agentMsgTransformer,
      this, this, this))
  }

  // For each sharded actor, there will be one region actor per type per node. The region
  // actor manages all the shard actors. See https://docs.google.com/drawings/d/1vyjsGYjEQtvQbwWVFditnTXP-JyhIIrMc2FATy4-GVs/edit
  def sendCmdToRegionActor(regionTypeName: String, toEntityId: String, cmd: Any): Future[Any] = {
    val regionActorRef = ClusterSharding.get(context.system).shardRegion(regionTypeName)
    regionActorRef ? ForIdentifier(toEntityId, cmd)
  }

    /*
    We call this function when we want to create a pairwise actor. It creates a key
    and the pairwise actor as well. The pairwise actor is effectively an "endpoint", since
    it is where you will receive messages from the other side.
     */
  def setupCreateKeyEndpoint(forDID: DID, agentKeyDID: DID, endpointDetailJson: String): Future[Any] = {
    val endpointDetail = DefaultMsgCodec.fromJson[CreateKeyEndpointDetail](endpointDetailJson)
    val cmd = SetupCreateKeyEndpoint(agentKeyDID, forDID, endpointDetail.ownerDID,
      endpointDetail.ownerAgentKeyDID, endpointDetail.ownerAgentActorEntityId, Option(getProtocolIdDetail))
    sendCmdToRegionActor(endpointDetail.regionTypeName, newEndpointActorEntityId, cmd)
  }

  def setupNewAgentEndpoint(forDID: DID, agentKeyDID: DID, endpointDetailJson: String): Future[Any] = {
    val endpointSetupDetail = DefaultMsgCodec.fromJson[CreateAgentEndpointDetail](endpointDetailJson)
    sendCmdToRegionActor(endpointSetupDetail.regionTypeName, endpointSetupDetail.entityId,
      SetupAgentEndpoint(forDID, agentKeyDID))
  }

  //NOTE: this method is used to compute entity id of the new pairwise actor this protocol will use
  // in 'setupCreateKeyEndpoint' method. The reason behind using 'entityId' of this protocol actor as a seed,
  // so that, in later stage (once endpoint has created), if this protocol actor needs (like for agent provisioning etc)
  // to reach out to same pairwise actor, then, it can use below function to compute same entity id which was
  // created during 'setupCreateKeyEndpoint'.
  //TODO: We may wanna come back to this and find better solution.
  def newEndpointActorEntityId: String = {
    getNewActorIdFromSeed(entityId)
  }

  def getProtocolIdDetail: ProtocolIdDetail = ProtocolIdDetail(protoRef, entityId)

  lazy val driver: Option[Driver] = {
    val parameter = ActorDriverGenParam(context.system, appConfig, agentActorContext.protocolRegistry,
      agentActorContext.generalCache, agentActorContext.agentMsgRouter, msgForwarder)
    agentActorContext.protocolRegistry.generateDriver(definition, parameter)
  }
  val sendsMsgs = new MsgSender

  //TODO move agentActorContext.smsSvc to be handled here (uses a future)
  class MsgSender extends SendsMsgsForContainer[M](this) {

    def send(pmsg: ProtocolOutgoingMsg): Unit = {
      val fromAgentId = ParticipantUtil.agentId(pmsg.from)
      agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(fromAgentId, pmsg))
      MsgProgressTracker.recordProtoMsgStatus(definition, pinstId, "sent-to-agent-actor",
        pmsg.requestMsgId, outMsg = Option(pmsg.msg))
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
        agentActorContext.remoteMsgSendingSvc)
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
      changeReceiverToDataStoring()
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
      changeReceiverToDataStoring()
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
      changeReceiverToRetrieve()
      val cmd = GetSegmentedState(segmentKey)
      sendToSegmentedRegion(segmentAddress, cmd)
    }

    def readStorageState(segmentAddress: SegmentAddress, segmentKey: SegmentKey, storageRef: StorageReferenceStored): Unit = {
      changeReceiverToRetrieve()
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
    getRoster.selfId_!
  )

  override lazy val wallet = new WalletAccessController(grantedAccessRights, walletAccessImpl)

  override lazy val ledger = new LedgerAccessController(
    grantedAccessRights,
    LedgerAccessApi(agentActorContext.ledgerSvc, wallet)
  )

  override lazy val urlShortening: UrlShorteningAccess = ???

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

  override def system: ActorSystem = agentActorContext.system

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
  * This is used to update delivery status of the message.
  * Currently it is used by Connecting protocol and UserAgentPairwise both
  * @param uid - unique message id
  * @param to - delivery destination (phone no, remote agent DID, edge DID etc)
  * @param statusCode - new status code
  * @param statusDetail - status detail
  */
case class UpdateMsgDeliveryStatus(uid: MsgId, to: String, statusCode: String,
                                   statusDetail: Option[String]) extends Control with ActorMessage

/**
  * Purpose of this service is to provide a way for protocol to schedule a message for itself
  */
trait MsgQueueServiceProvider {
  def addToMsgQueue(msg: Any): Unit
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
case class InitProtocol(domainId: DomainId, parameters: Set[Parameter]) extends ActorMessage

/**
  * This is used by this actor during protocol initialization process.
  * It is sent to the message forwarder (which is available in ProtocolCmd)
  * @param stateKeys - set of keys/names whose value is needed by the protocol.
  */
case class InitProtocolReq(stateKeys: Set[String]) extends ActorMessage

case class ProtocolCmd(msg: Any, metadata: ProtocolMetadata) extends ActorMessage

/*
  walletSeed: actor protocol container needs to access/provide wallet service
  and for that it needs this walletSeed

  forwarder: actor reference for the launcher (who forwards the incoming msg to the actor protocol container)
  which is then provided into driver and driver uses it to reach to the same agent (launcher)
  who originally forwarded the msg
 */
case class ProtocolMetadata(threadContextDetail: ThreadContextDetail,
                            walletId: String,
                            forwarder: ActorRef)

case class ProtocolIdDetail(protoRef: ProtoRef, pinstId: PinstId)

/**
 * incoming msg envelope
 * @param msg
 * @param msgType
 * @param to
 * @param frm
 * @param msgId
 * @param thId
 * @tparam A
 */
case class MsgEnvelope[A](msg: A,
                          msgType: MsgType,
                          to: ParticipantId,
                          frm: ParticipantId,
                          msgId: Option[MsgId]=None,
                          thId: Option[ThreadId]=None) extends TypedMsgLike[A] with ActorMessage {
  def typedMsg: TypedMsg[A] = TypedMsg(msg, msgType)
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