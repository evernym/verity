package com.evernym.verity.actor.persistence.recovery.base

import akka.persistence.testkit.{PersistenceTestKitSnapshotPlugin, SnapshotMeta}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.msgrouter.RoutingAgentUtil
import com.evernym.verity.actor.persistence.DefaultPersistenceEncryption
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated}
import com.evernym.verity.actor.{DeprecatedEventMsg, DeprecatedStateMsg, MappingAdded, PersistentMsg, RouteSet}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.transformations.transformers.v1._
import com.evernym.verity.transformations.transformers.legacy._
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.{<=>, legacy, v1}
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.config.{Config, ConfigFactory}

/**
 * common/base code to store events and adding data to wallet store
 */
trait BasePersistentStore
  extends ActorSpec
    with BasicSpec {

  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """
        |""".stripMargin)
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )

  def createWallet(walletId: String): Unit = {
    walletAPI.createWallet(WalletAPIParam(walletId))
  }

  def createNewKey(walletId: String, seed: Option[String]=None): NewKeyCreated = {
    walletAPI.createNewKey(CreateNewKey(seed = seed))(WalletAPIParam(walletId))
  }

  def storeAgentRoute(agentDID: DID, actorTypeId: Int, address: EntityId)
                     (implicit pp: PersistParam = PersistParam()): Unit = {
    val routeStoreEntityId = RoutingAgentUtil.getBucketEntityId(agentDID)
    addEventsToPersistentStorage(PersistenceIdParam(AGENT_ROUTE_STORE_REGION_ACTOR_NAME, routeStoreEntityId),
      scala.collection.immutable.Seq(
        RouteSet(agentDID, actorTypeId, address)
      )
    )(pp.copy(encryptionKey = Option("4k3kejd845k4k3j")))
  }

  /**
   * key value mapper is the actor which gets updated when we setup agency agent
   * this method will store proper event to setup the agency DID in that actor
   */
  def storeAgencyDIDKeyValueMapping(agencyDID: DID)(implicit pp: PersistParam = PersistParam()): Unit = {
    addEventsToPersistentStorage(PersistenceIdParam(CLUSTER_SINGLETON_MANAGER, KEY_VALUE_MAPPER_ACTOR_NAME),
      scala.collection.immutable.Seq(
        MappingAdded(AGENCY_DID_KEY, agencyDID)
      )
    )(pp.copy(encryptionKey = Option("krkifcjk4io5k4k4kl")))
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
    val transformer = getEventTransformer(persistenceId, objectCodeMapper)
    val transformedEvents = events.map(evt => transformer.execute(evt))
    persTestKit.persistForRecovery(persistenceId.toString, transformedEvents)
  }

  /**
   * adds given events to persistent storage (in memory storage) for given persistence id
   * @param persistenceId persistence id
   * @param snapshots snapshots to be stored in persistent storage
   */
  def addSnapshotToPersistentStorage(persistenceId: PersistenceIdParam,
                                     snapshots: scala.collection.immutable.Seq[Any])
                                    (implicit pp: PersistParam = PersistParam()): Unit = {
    val objectCodeMapper = pp.objectCodeMapper.getOrElse(DefaultObjectCodeMapper)
    validateObjectCodeMapping(objectCodeMapper, snapshots)
    val transformer = getStateTransformer(persistenceId, objectCodeMapper)
    val transformedSnapshots = snapshots.zipWithIndex.map { case (state, index) =>
      val transformedState = transformer.execute(state)
      (SnapshotMeta(index, index), transformedState)
    }
    snapTestKit.persistForRecovery(persistenceId.toString, transformedSnapshots)
  }

  private def validateObjectCodeMapping(objectCodeMapper: ObjectCodeMapperBase,
                        events: scala.collection.immutable.Seq[Any])(implicit pp: PersistParam): Unit = {
    if (pp.validateEventsCodeMapping) {
      events.foreach (evt => objectCodeMapper.codeFromObject(evt))
    }
  }

  private def getEventTransformer(persistenceIdParam: PersistenceIdParam,
                                  objectCodeMapper: ObjectCodeMapperBase)(implicit pp: PersistParam)
  : <=>[Any, _ >: TransformedMsg] = {
    getTransformer(persistenceIdParam, objectCodeMapper, "event")
  }

  private def getStateTransformer(persistenceIdParam: PersistenceIdParam,
                                  objectCodeMapper: ObjectCodeMapperBase)(implicit pp: PersistParam)
  : <=>[Any, _ >: TransformedMsg] = {
    getTransformer(persistenceIdParam, objectCodeMapper, "state")
  }

  private def getTransformer(persistenceIdParam: PersistenceIdParam,
                             objectCodeMapper: ObjectCodeMapperBase,
                             objectType: String)(implicit pp: PersistParam)
    : <=>[Any, _ >: TransformedMsg] = {
    val encKey = pp.encryptionKey.getOrElse(
      DefaultPersistenceEncryption.getEventEncryptionKeyWithoutWallet(persistenceIdParam.entityId, appConfig))
    (pp.transformerId, objectType) match {
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, "event")  => legacy.createLegacyEventTransformer(encKey, objectCodeMapper)
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, "state")  => legacy.createLegacyStateTransformer(encKey, objectCodeMapper)
      case (PERSISTENCE_TRANSFORMATION_ID_V1, _)            => v1.createPersistenceTransformerV1(encKey, objectCodeMapper)
      case other                                            => throw new RuntimeException("transformer not supported for: " + other)
    }
  }

  type TransformedMsg = DeprecatedEventMsg with DeprecatedStateMsg with PersistentMsg
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