package com.evernym.verity.actor.agent

import java.time.ZonedDateTime
import akka.pattern.ask
import com.evernym.verity.util2.Exceptions.InternalServerErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.{ActorMessage, AuthKeyAdded, FirstProtoMsgSent, ProtoMsgReceivedOrderIncremented, ProtoMsgSenderOrderIncremented, ProtocolIdDetailSet, ThreadContextStored}
import com.evernym.verity.actor.agent.agency.GetAgencyIdentity
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute}
import com.evernym.verity.actor.agent.relationship.{AuthorizedKey, DidDoc, DidDocBuilder, DidDocBuilderParam, KeyId, Relationship}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.constants.Constants._
import com.evernym.verity.observability.logs.LoggingUtil.getAgentIdentityLoggerByClass
import com.evernym.verity.protocol.engine._
import com.evernym.verity.actor.agent.state.base.{AgentStateInterface, AgentStateUpdateInterface}
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.msg_tracer.progress_tracker.{HasMsgProgressTracker, TrackingIdParam}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.agentmsg.msgcodec.UnknownFormatType
import com.evernym.verity.cache.{AGENCY_IDENTITY_CACHE_FETCHER, AGENT_ACTOR_CONFIG_CACHE_FETCHER, KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER}
import com.evernym.verity.cache.base.{Cache, FetcherParam, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{AgentConfigCacheFetcher, CacheValueFetcher, GetAgencyIdentityCacheParam}
import com.evernym.verity.config.ConfigConstants.{AKKA_SHARDING_REGION_NAME_USER_AGENT, VDR_LEGACY_DEFAULT_NAMESPACE, VERITY_ENDORSER_DEFAULT_DID}
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgType, TypedMsgLike}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.metrics.CustomMetrics.AS_ACTOR_AGENT_STATE_SIZE
import com.evernym.verity.observability.metrics.{InternalSpan, MetricsUnit, MetricsWriter}
import com.evernym.verity.protocol.container.actor.ProtocolIdDetail
import com.evernym.verity.protocol.engine.registry.PinstIdResolver
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vdr.VDRUtil
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

/**
 * common interface for any type of agent actor (agencyAgent, agencyPairwiseAgent, userAgent, userPairwiseAgent)
 */
trait AgentCommon
  extends AgentStateUpdateInterface
    with AgentIdentity
    with HasAgentWallet
    with HasSetRoute
    with HasMsgProgressTracker
    with ResourceUsageCommon { this: AgentPersistentActor =>

  private implicit def executionContext: ExecutionContext = futureExecutionContext

  def metricsWriter : MetricsWriter

  def agentCommonCmdReceiver[A]: Receive = {
    case _: AgentActorDetailSet    => //nothing to do
    case SetMissingRoute =>
      val sndr = sender()
      state
        .myDid
        .map(d => setRoute(d, state.thisAgentKeyDID))
        .getOrElse(Future.failed(throw new RuntimeException("myDID not set")))
        .map { _ =>
          sndr ! MissingRouteSet
        }
  }

  def receiveAgentInitCmd: Receive = {
    case us: UpdateState    =>
      updateAgencyDidPair(us.agencyDidPair)
      updateRelationship(us.relationship)
      val events = us.persistAuthKeys.map(pk => AuthKeyAdded(pk.keyId, pk.verKey))
      if (events.nonEmpty) {
        writeAllWithoutApply(events.toList)
      }
      sender() ! Done
    case FixAgentState => fixAgentState()
  }

  def agentCommonEventReceiver: Receive = {
    //NOTE: ProtocolIdDetailSet is a proto buf event which stores mapping between protocol reference
    // and corresponding protocol identifier
    //There is a method 'getPinstId' below, which uses that stored state/mapping to know if a
    // protocol actor (for given protocol reference),
    // is already created in the given context(like agency agent pairwise or user agent pairwise actor etc),
    // and if it is, then it uses that identifier to send incoming message to the protocol actor,
    // or else creates a new protocol actor.
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

    case aka: AuthKeyAdded =>
      _addedAuthKeys = _addedAuthKeys + AuthKey(aka.keyId, aka.verKey)
  }


  //these would be auth keys added during actor recovery for
  // auth keys with empty ver key for which it has to call wallet api to get ver key
  var _addedAuthKeys = Set.empty[AuthKey]

  var isThreadContextMigrationFinished: Boolean = false

  type StateType <: AgentStateInterface
  def state: StateType

  override lazy val logger: Logger = getAgentIdentityLoggerByClass(this, getClass)(context.system)

  def agentActorContext: AgentActorContext
  def walletAPI: WalletAPI = agentActorContext.walletAPI
  def agentWalletId: Option[String] = state.agentWalletId
  def agentMsgTransformer: AgentMsgTransformer = agentActorContext.agentMsgTransformer

  def agencyDIDReq: DidStr = state.agencyDIDReq

  def ownerDID: Option[DidStr]
  def ownerDIDReq: DidStr = ownerDID.getOrElse(throw new RuntimeException("owner DID not found"))
  def domainId: DomainId = ownerDIDReq

  def ownerAgentKeyDIDPair: Option[DidPair]

  //tracking ids
  def trackingIdParam: TrackingIdParam = TrackingIdParam(domainId, state.myDid, state.theirDid)

  lazy val cacheFetchers: Map[FetcherParam, CacheValueFetcher] = Map (
    AGENT_ACTOR_CONFIG_CACHE_FETCHER -> new AgentConfigCacheFetcher(agentActorContext.agentMsgRouter, agentActorContext.appConfig, futureExecutionContext)
  )
  /**
   * per agent actor cache
   */
  lazy val agentCache = new Cache("AC", cacheFetchers, metricsWriter, futureExecutionContext)

  /**
   * general/global (per actor system) cache
   */
  lazy val generalCache: Cache = agentActorContext.generalCache

  lazy val defaultEndorserDid: String = appConfig.getStringOption(VERITY_ENDORSER_DEFAULT_DID).getOrElse("")

  def updateAgentWalletId(actorEntityId: String): Unit = {
    if (agentWalletId.nonEmpty && ! agentWalletId.contains(actorEntityId))
      throw new InternalServerErrorException(ALREADY_EXISTS.statusCode, Option("agent wallet id already set to different value"))
    try {
      setAgentWalletId(actorEntityId)
      logger.debug(s"wallet id successfully initialized for actorEntityId: $actorEntityId")
    } catch {
      case e: Exception =>
        logger.error(s"wallet id initialization failed for actorEntityId: $actorEntityId")
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def setAgentActorDetail(didPair: DidPair): Future[Any] = {
    agentActorContext.agentMsgRouter.execute(GetRoute(didPair.DID)) flatMap {
      case Some(aa: ActorAddressDetail) => self ? SetAgentActorDetail(didPair, aa.address)
      case None => Future.successful("agent not yet created")
    }
  }

  def agencyDidPairFut(): Future[DidPair] = agencyDidPairFutByCache()

  def agencyDidPairFutByCache(): Future[DidPair] = {
    val agencyDidPair = state.agencyDIDPair
    val agencyAuthKey = state.agencyDIDPair.flatMap(adp => _addedAuthKeys.find(_.keyId == adp.DID))
    (agencyDidPair, agencyAuthKey) match {
      case (Some(adp), _) if adp.DID.nonEmpty && adp.verKey.nonEmpty => Future.successful(adp)
      case (Some(adp), Some(ak)) if adp.DID.nonEmpty => Future.successful(adp.copy(verKey = ak.verKey))
      case (Some(adp), _) if adp.DID.nonEmpty && adp.verKey.isEmpty => agencyDidPairFutByCache(adp.DID)
      case _ =>
        val gcop = GetCachedObjectParam(KeyDetail(AGENCY_DID_KEY, required = false), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER)
        generalCache.getByParamAsync(gcop).flatMap { cqr =>
          agencyDidPairFutByCache(cqr.getAgencyDIDReq)
        }
    }
  }

  def agencyDidPairFutByCache(agencyDID: DidStr): Future[DidPair] = {
    val gadp = GetAgencyIdentityCacheParam(agencyDID, GetAgencyIdentity(agencyDID, getEndpoint = false))
    val gadfcParam = GetCachedObjectParam(KeyDetail(gadp, required = true), AGENCY_IDENTITY_CACHE_FETCHER)
    generalCache.getByParamAsync(gadfcParam)
      .map(cqr => DidPair(agencyDID, cqr.getAgencyInfoReq(agencyDID).verKeyReq))
  }

  implicit def didDocBuilderParam: DidDocBuilderParam = DidDocBuilderParam(appConfig, state.thisAgentKeyDID)

  def resolvePinstId(protoDef: ProtoDef, resolver: PinstIdResolver, relationshipId: Option[RelationshipId],
                     threadId: ThreadId, msg: Option[TypedMsgLike]=None): PinstId = {
    resolver.resolve(
      protoDef,
      domainId,
      relationshipId,
      Option(threadId),
      msg.flatMap(protoDef.protocolIdSuffix),
      state.contextualId
    )
  }

  def updateRelationship(newRelationship: Relationship): Unit
  def updateAgencyDidPair(dp: DidPair): Unit

  override def postSuccessfulActorRecovery(): Unit = {
    Option(state).foreach { s =>
      isThreadContextMigrationFinished = s.currentThreadContextSize == 0
      logStateSizeMetrics(s)
    }
  }

  def logStateSizeMetrics(s: StateType): Unit = {
    try {
      val stateSize = s.serializedSize
      if (stateSize >= 0) { // so only states that can calculate size are part the metric
        metricsWriter.histogramUpdate(
          AS_ACTOR_AGENT_STATE_SIZE,
          MetricsUnit.Information.Bytes,
          stateSize,
          Map("actor_class" -> this.getClass.getSimpleName)
        )
      }
    } catch {
      case e: RuntimeException =>
        logger.error(s"[$persistenceId] error occurred while calculating state size => " +
          s"state: $s, error: ${e.getMessage}, exception stack trace: " +
          s"${Exceptions.getStackTraceAsSingleLineString(e)}")
    }
  }

  override def postActorRecoveryCompleted(): Future[Any] = {
    preAgentStateFix().flatMap { _ =>
      self ? FixAgentState
    }.flatMap { _ =>
      postAgentStateFix()
    }
  }

  def fixAgentState(): Unit = {
    metricsWriter.runWithSpan("fixAgentState", s"${getClass.getSimpleName}", InternalSpan) {
      val sndr = sender()
      val preAddedAuthKeys = _addedAuthKeys
      val preAgencyDidPair = state.agencyDIDPair
      state.relationship match {
        case Some(rel) =>
          val oldAuthKeys = getAllAuthKeys(rel)
          val result = for (
            updatedMyDidDoc     <- updatedDidDoc(preAddedAuthKeys, rel.myDidDoc);
            updatedThoseDidDocs <- updatedDidDocs(preAddedAuthKeys, rel.thoseDidDocs);
            newAgencyDIDPair    <- agencyDidPairFut()
          ) yield {
            val updatedRel =
              rel
                .update(_.myDidDoc.setIfDefined(updatedMyDidDoc))
                .update(_.thoseDidDocs.setIfDefined(Option(updatedThoseDidDocs)))
            val newAuthKeys = getAllAuthKeys(updatedRel)
            val authKeyToBePersisted =
              computeAgencyAuthKeyToBePersisted(preAgencyDidPair, newAgencyDIDPair) ++
                computeAuthKeyToBePersisted(preAddedAuthKeys, oldAuthKeys, newAuthKeys)
            self.tell(UpdateState(newAgencyDIDPair, updatedRel, authKeyToBePersisted), sndr)
          }
          result.recover {
            case e: RuntimeException =>
              handleException(e, self)
          }
        case None =>
          sndr ! Done
      }
    }
  }

  def getAllAuthKeys(rel: Relationship): List[AuthorizedKey] = {
    val didDocs = rel.myDidDoc ++ rel.thoseDidDocs
    didDocs.flatMap(_.getAuthorizedKeys.keys).toList
  }

  def computeAgencyAuthKeyToBePersisted(preAgencyDidPair: Option[DidPair],
                                        postAgencyDidPair: DidPair): Set[AuthKey] = {
    if (preAgencyDidPair.contains(postAgencyDidPair) ||
      _addedAuthKeys.exists(_.keyId == postAgencyDidPair.DID)) Set.empty
    else Set(AuthKey(postAgencyDidPair.DID, postAgencyDidPair.verKey))
  }

  def computeAuthKeyToBePersisted(explicitlyAddedAuthKeys: Set[AuthKey],
                                  authKeysBeforeDidDocUpdate: List[AuthorizedKey],
                                  authKeysAfterDidDocUpdate: List[AuthorizedKey]): Set[AuthKey] = {
    authKeysAfterDidDocUpdate.filter { nak =>
      nak.verKeyOpt.isDefined &&
        authKeysBeforeDidDocUpdate.exists(oak => oak.keyId == nak.keyId && oak.verKeyOpt.isEmpty) &&
        ! explicitlyAddedAuthKeys.exists(_.keyId == nak.keyId)
    }.map { nak =>
      AuthKey(nak.keyId, nak.verKey)
    }.toSet
  }

  def preAgentStateFix(): Future[Any] = {
    Future.successful("pre agent state fix")
  }

  def postAgentStateFix(): Future[Any] = {
    Future.successful("post agent state fix")
  }

  def updatedDidDoc(explicitlyAddedAuthKeys: Set[AuthKey],
                    didDocOpt: Option[DidDoc]): Future[Option[DidDoc]] = {
    didDocOpt.map { dd =>
      updatedDidDocs(explicitlyAddedAuthKeys, Seq(dd)).map(_.headOption)
    }.getOrElse(Future.successful(None))
  }

  def updatedDidDocs(explicitlyAddedAuthKeys: Set[AuthKey],
                     didDocs: Seq[DidDoc]): Future[Seq[DidDoc]] =
    Future.traverse(didDocs) { dd =>
      DidDocBuilder(futureExecutionContext, dd).updatedDidDocWithMigratedAuthKeys(explicitlyAddedAuthKeys, agentWalletAPI)
    }

  def fqDid(did: DidStr): DidStr = VDRUtil.toFqDID(did, vdrDefaultNamespace)

  lazy val vdrDefaultNamespace: String = appConfig.getStringReq(VDR_LEGACY_DEFAULT_NAMESPACE)

  lazy val isVAS: Boolean =
    appConfig
      .getStringOption(AKKA_SHARDING_REGION_NAME_USER_AGENT)
      .contains("VerityAgent")
}

case class UpdateState(agencyDidPair: DidPair, relationship: Relationship, persistAuthKeys: Set[AuthKey]) extends ActorMessage

case class AuthKey(keyId: KeyId, verKey: VerKeyStr) {
  require(verKey.nonEmpty)
}

/**
 * This is information about "self rel" agent actor (either AgencyAgent or UserAgent)
 *
 * @param didPair
 * @param actorEntityId entity id of the actor which belongs to the self relationship actor
 */
case class SetAgentActorDetail(didPair: DidPair, actorEntityId: String) extends ActorMessage
case class AgentActorDetailSet(didPair: DidPair, actorEntityId: String) extends ActorMessage

case class SetAgencyIdentity(didPair: DidPair) extends ActorMessage
case class AgencyIdentitySet(didPair: DidPair) extends ActorMessage

trait SponsorRelCompanion {
  def apply(sponsorId: Option[String], sponseeId: Option[String]): SponsorRel =
    new SponsorRel(sponsorId.getOrElse(""), sponseeId.getOrElse(""))

  def empty: SponsorRel = new SponsorRel("", "")
}

/**
 *
 * @param newAgentKeyDIDPair DID belonging to the new agent ver key
 * @param forDIDPair pairwise DID for which new pairwise actor needs to be setup
 * @param mySelfRelDID my self relationship DID
 * @param ownerAgentKeyDIDPair owner's agent's DID Pair
 * @param ownerAgentActorEntityId entity id of owner's agent actor
 * @param pid
 */
case class SetupCreateKeyEndpoint(newAgentKeyDIDPair: DidPair,
                                  forDIDPair: DidPair,
                                  mySelfRelDID: DidStr, //domain id
                                  ownerAgentKeyDIDPair: Option[DidPair] = None,
                                  ownerAgentActorEntityId: Option[EntityId]=None,
                                  pid: Option[ProtocolIdDetail]=None,
                                  publicIdentity: Option[DidPair]=None,
                                  ownerName: Option[String]=None) extends ActorMessage {

  def ownerAgentKeyDIDPairReq: DidPair = ownerAgentKeyDIDPair.getOrElse(
    throw new RuntimeException("ownerAgentKeyDIDPair not supplied")
  )
  def ownerAgentKeyDIDReq: DidStr = ownerAgentKeyDIDPairReq.DID
  def ownerAgentKeyDIDVerKeyReq: VerKeyStr = ownerAgentKeyDIDPairReq.verKey
}

trait SetupEndpoint extends ActorMessage {
  def ownerDIDPair: DidPair
  def agentKeyDIDPair: DidPair

  def ownerDID: DidStr = ownerDIDPair.DID
  def ownerDIDVerKey: VerKeyStr = ownerDIDPair.verKey
  def agentKeyDID: DidStr = agentKeyDIDPair.DID
  def agentKeyDIDVerKey: VerKeyStr = agentKeyDIDPair.verKey
}

case class SetupAgentEndpoint(ownerDIDPair: DidPair,
                              agentKeyDIDPair: DidPair) extends SetupEndpoint

case class SetupAgentEndpoint_V_0_7 (threadId: ThreadId,
                                     ownerDIDPair: DidPair,
                                     agentKeyDIDPair: DidPair,
                                     requesterVerKey: VerKeyStr,
                                     sponsorRel: Option[SponsorRel]=None) extends SetupEndpoint

import com.evernym.verity.util.TimeZoneUtil._

trait AgentMsgBase {
  def `type`: String
  def creationTimeInMillis: Long
  def lastUpdatedTimeInMillis: Long
  def getType: String = `type`

  def creationDateTime: ZonedDateTime = getZonedDateTimeFromMillis(creationTimeInMillis)(UTCZoneId)
  def lastUpdatedDateTime: ZonedDateTime = getZonedDateTimeFromMillis(lastUpdatedTimeInMillis)(UTCZoneId)
}


trait ThreadBase {
  def sender_order: Option[Int]
  def received_orders: Map[String, Int]

  def senderOrder: Option[Int] = sender_order
  def senderOrderReq: Int = sender_order.getOrElse(0)
  def receivedOrders: Option[Map[String, Int]] = Option(received_orders)
}

trait MsgDeliveryStatusBase {
  def lastUpdatedTimeInMillis: Long
  def lastUpdatedDateTime: ZonedDateTime = getZonedDateTimeFromMillis(lastUpdatedTimeInMillis)(UTCZoneId)
}

trait PayloadWrapperCompanion {
  def apply(msg: Array[Byte], metadata: Option[PayloadMetadata]): PayloadWrapper = {
    PayloadWrapper(ByteString.copyFrom(msg), metadata)
  }
}

trait PayloadWrapperBase {
  def msg: Array[Byte] = msgBytes.toByteArray
  def msgBytes: ByteString
  def metadata: Option[PayloadMetadata]
  def msgPackFormat: Option[MsgPackFormat] = metadata.map(md => md.msgPackFormat)
}

/**
 * this is only used when this agent has packed a message and it knows its metadata
 * (message type string, message pack version etc)
 * this should NOT be used when this agent is acting as a proxy and just storing a received packed message
 * (as in that case, it may/won't have idea about how that message is packed)
 */
trait PayloadMetadataCompanion {
  def apply(msgTypeStr: String, msgPackFormat: MsgPackFormat): PayloadMetadata = {
    PayloadMetadata(msgTypeStr, msgPackFormat.toString)
  }
  def apply(msgType: MsgType, msgPackFormat: MsgPackFormat): PayloadMetadata = {
    PayloadMetadata(MsgFamily.typeStrFromMsgType(msgType), msgPackFormat.toString)
  }
}

trait PayloadMetadataBase {
  def msgPackFormatStr: String
  def msgPackFormat: MsgPackFormat = MsgPackFormat.fromString(msgPackFormatStr)
}

object FixAgentState extends ActorMessage

case object SetMissingRoute extends ActorMessage
case object MissingRouteSet extends ActorMessage