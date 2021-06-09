package com.evernym.verity.actor.persistence.recovery.base

import akka.persistence.testkit.{PersistenceTestKitSnapshotPlugin, SnapshotMeta}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.msgrouter.RoutingAgentUtil
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.persistence.DefaultPersistenceEncryption
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.actor.testkit.actor.MockAppConfig
import com.evernym.verity.actor.testkit.HasTestActorSystem
import com.evernym.verity.actor.wallet.{Close, CreateDID, CreateNewKey, CreateWallet, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.actor.{DeprecatedEventMsg, DeprecatedStateMsg, LegacyRouteSet, MappingAdded, PersistentMsg, RouteSet}
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.KEY_ED25519
import com.evernym.verity.transformations.transformers.v1._
import com.evernym.verity.transformations.transformers.legacy._
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.testkit.HasTestWalletAPI
import com.evernym.verity.transformations.transformers.{<=>, legacy, v1}
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.config.{Config, ConfigFactory}

/**
 * common/base code to store events and adding data to wallet store
 */
trait BasePersistentStore
  extends HasTestActorSystem
    with MockAppConfig
    with HasTestWalletAPI {

  lazy val keyValueMapperPersistenceId = PersistenceIdParam(CLUSTER_SINGLETON_MANAGER, KEY_VALUE_MAPPER_ACTOR_NAME)
  lazy val keyValueMapperEncKey = appConfig.getConfigStringReq(CommonConfig.SECRET_KEY_VALUE_MAPPER)
  lazy val agentRouteStoreEncKey = appConfig.getConfigStringReq(CommonConfig.SECRET_ROUTING_AGENT)

  def createWallet(walletId: String): Unit = {
    testWalletAPI.executeSync[WalletCreated.type](CreateWallet())(WalletAPIParam(walletId))
  }

  def createDID(walletId: String): NewKeyCreated = {
    testWalletAPI.executeSync[NewKeyCreated](CreateDID(KEY_ED25519))(WalletAPIParam(walletId))
  }

  def createNewKey(walletId: String, seed: Option[String]=None): NewKeyCreated = {
    testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = seed))(WalletAPIParam(walletId))
  }

  def storeTheirKey(walletId: String, didPair: DidPair): TheirKeyStored = {
    storeTheirKey(walletId, didPair.DID, didPair.verKey)
  }

  def storeTheirKey(walletId: String, theirDID: DID, theirDIDVerKey: VerKey): TheirKeyStored = {
    testWalletAPI.executeSync[TheirKeyStored](StoreTheirKey(theirDID, theirDIDVerKey))(WalletAPIParam(walletId))
  }

  def closeWallet(walletId: String): Done.type = {
    testWalletAPI.executeSync[Done.type](Close())(WalletAPIParam(walletId))
  }

  def storeAgentRoute(agentDID: DID, actorTypeId: Int, address: EntityId)
                     (implicit pp: PersistParam = PersistParam()): Unit = {
    val persistenceId = PersistenceIdParam(ROUTE_REGION_ACTOR_NAME, agentDID)
    addEventsToPersistentStorage(persistenceId,
      scala.collection.immutable.Seq(
        RouteSet(actorTypeId, address)
      )
    )(pp.copy(encryptionKey = Option(agentRouteStoreEncKey)))
  }

  def storeLegacyAgentRoute(agentDID: DID, actorTypeId: Int, address: EntityId)
                           (implicit pp: PersistParam = PersistParam()): Unit = {
    val entityId = RoutingAgentUtil.getBucketEntityId(agentDID)
    val persistenceId = PersistenceIdParam(LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME, entityId)
    addEventsToPersistentStorage(persistenceId,
      scala.collection.immutable.Seq(
        LegacyRouteSet(agentDID, actorTypeId, address)
      )
    )(pp.copy(encryptionKey = Option(agentRouteStoreEncKey)))
  }

  /**
   * key value mapper is the actor which gets updated when we setup agency agent
   * this method will store proper event to setup the agency DID in that actor
   */
  def storeAgencyDIDKeyValueMapping(agencyDID: DID)(implicit pp: PersistParam = PersistParam()): Unit = {

    addEventsToPersistentStorage(keyValueMapperPersistenceId,
      scala.collection.immutable.Seq(
        MappingAdded(AGENCY_DID_KEY, agencyDID)
      )
    )(pp.copy(encryptionKey = Option(keyValueMapperEncKey)))
  }

  def getEvents(pp: PersistenceIdParam, encryptionKey: Option[String]=None): Seq[Any] = {
    val events = persTestKit.persistedInStorage(pp.toString)
    val transformer = getTransformerFor(pp, encryptionKey)
    events.map { e =>
      transformer.undo(e.asInstanceOf[PersistentMsg])
    }
  }

  def getSnapshot[T](pp: PersistenceIdParam): T = {
    val rawEvent = snapTestKit.expectNextPersistedType[PersistentMsg](pp.toString)
    val transformer = getTransformerFor(pp, None)
    transformer.undo(rawEvent).asInstanceOf[T]
  }

  def getTransformerFor(pp: PersistenceIdParam, encryptionKey: Option[String]=None): Any <=> PersistentMsg = {
    val encKey = encryptionKey.getOrElse(
      DefaultPersistenceEncryption.getEventEncryptionKeyWithoutWallet(pp.entityId, appConfig))
    getTransformer(encKey)
  }

  /**
   * adds given events to persistent storage (in memory storage) for given persistence id
   * @param persistenceId persistence id
   * @param events events to be stored in persistent storage
   */
  def addEventsToPersistentStorage(persistenceId: PersistenceIdParam,
                                   events: scala.collection.immutable.Seq[Any])
                                  (implicit pp: PersistParam = PersistParam()): Unit = {
    val objectCodeMapper = pp.objectCodeMapper.getOrElse(DefaultObjectCodeMapper)
    validateObjectCodeMapping(objectCodeMapper, events)
    val transformer = getEventTransformer(persistenceId.entityId, objectCodeMapper)
    val transformedEvents = events.map(evt => transformer.execute(evt))
    persTestKit.persistForRecovery(persistenceId.toString, transformedEvents)
  }

  def addSnapshotToPersistentStorage(persistenceId: PersistenceIdParam,
                                     snapshot: Any)(implicit pp: PersistParam = PersistParam()): Unit = {
    addSnapshotsToPersistentStorage(persistenceId, Seq(snapshot))
  }
  /**
   * adds given events to persistent storage (in memory storage) for given persistence id
   * @param persistenceId persistence id
   * @param snapshots snapshots to be stored in persistent storage
   */
  def addSnapshotsToPersistentStorage(persistenceId: PersistenceIdParam,
                                      snapshots: Seq[Any])
                                     (implicit pp: PersistParam = PersistParam()): Unit = {
    val objectCodeMapper = pp.objectCodeMapper.getOrElse(DefaultObjectCodeMapper)
    validateObjectCodeMapping(objectCodeMapper, snapshots)
    val transformer = getStateTransformer(persistenceId.entityId, objectCodeMapper)
    val transformedSnapshots = snapshots.zipWithIndex.map { case (state, index) =>
      val transformedState = transformer.execute(state)
      (SnapshotMeta(index, index), transformedState)
    }
    snapTestKit.persistForRecovery(persistenceId.toString, transformedSnapshots.toList)
  }

  private def validateObjectCodeMapping(objectCodeMapper: ObjectCodeMapperBase,
                                        events: Seq[Any])(implicit pp: PersistParam): Unit = {
    if (pp.validateEventsCodeMapping) {
      events.foreach (evt => objectCodeMapper.codeFromObject(evt))
    }
  }

  private def getEventTransformer(entityId: EntityId,
                                  objectCodeMapper: ObjectCodeMapperBase)(implicit pp: PersistParam)
  : <=>[Any, _ >: TransformedMsg] = {
    getTransformer(entityId, objectCodeMapper, "event")
  }

  private def getStateTransformer(entityId: EntityId,
                                  objectCodeMapper: ObjectCodeMapperBase)(implicit pp: PersistParam)
  : <=>[Any, _ >: TransformedMsg] = {
    getTransformer(entityId, objectCodeMapper, "state")
  }

  private def getTransformer(entityId: EntityId,
                             objectCodeMapper: ObjectCodeMapperBase,
                             objectType: String)(implicit pp: PersistParam)
    : <=>[Any, _ >: TransformedMsg] = {
    val encKey = pp.encryptionKey.getOrElse(
      DefaultPersistenceEncryption.getEventEncryptionKeyWithoutWallet(entityId, appConfig))
    (pp.transformerId, objectType) match {
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, "event")  =>
        legacy.createLegacyEventTransformer(encKey, objectCodeMapper)
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, "state")  =>
        legacy.createLegacyStateTransformer(encKey, objectCodeMapper)
      case (PERSISTENCE_TRANSFORMATION_ID_V1, _)            =>
        v1.createPersistenceTransformerV1(encKey, objectCodeMapper)
      case other                                            =>
        throw new RuntimeException("transformer not supported for: " + other)
    }
  }

  type TransformedMsg = DeprecatedEventMsg with DeprecatedStateMsg with PersistentMsg

  /**
   * to be overridden by test for overriding test specific configuration
   * @return
   */
  def overrideSpecificConfig: Option[Config] = None

  final override def overrideConfig: Option[Config] = Option {
    val baseConfig =
      ConfigFactory.empty()
        .withFallback(EventSourcedBehaviorTestKit.config)
        .withFallback(PersistenceTestKitSnapshotPlugin.config)
    overrideSpecificConfig match {
      case Some(sc) => baseConfig.withFallback(sc)
      case None     => baseConfig
    }
  }

  def getTransformer(encrKey: String): Any <=> PersistentMsg = createPersistenceTransformerV1(encrKey)
}

/**
 * parameter consisting
 */
object PersistParam {

  //this one will force to use legacy event transformer (legacyEventTransformer)
  def withLegacyTransformer(objectCodeMapper: ObjectCodeMapperBase): PersistParam =
    PersistParam(LEGACY_PERSISTENCE_TRANSFORMATION_ID,  objectCodeMapper = Option(objectCodeMapper))

  //all below methods will force to use latest event transformer (persistenceTransformerV1)
  def apply(): PersistParam = PersistParam(1, None, None)

  def apply(objectCodeMapper: ObjectCodeMapperBase): PersistParam =
    PersistParam(PERSISTENCE_TRANSFORMATION_ID_V1, None, objectCodeMapper = Option(objectCodeMapper))

  def apply(encryptionKey: String): PersistParam =
    PersistParam(PERSISTENCE_TRANSFORMATION_ID_V1, Option(encryptionKey), None)

  def apply(encryptionKey: String, objectCodeMapper: ObjectCodeMapperBase): PersistParam =
    PersistParam(PERSISTENCE_TRANSFORMATION_ID_V1, Option(encryptionKey), Option(objectCodeMapper))
}

case class PersistParam(transformerId: Int,
                        encryptionKey: Option[String] = None,
                        objectCodeMapper: Option[ObjectCodeMapperBase]=None,
                        validateEventsCodeMapping: Boolean = true)

case class PersistenceIdParam(entityTypeName: String, entityId: String) {
  override def toString = s"$entityTypeName-$entityId"
}