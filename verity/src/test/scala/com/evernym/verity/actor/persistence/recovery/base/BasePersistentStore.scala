package com.evernym.verity.actor.persistence.recovery.base

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.msgrouter.RoutingAgentUtil
import com.evernym.verity.actor.persistence.DefaultPersistenceEncryption
import com.evernym.verity.actor.persistence.object_code_mapper.DefaultObjectCodeMapper
import com.evernym.verity.actor.resourceusagethrottling.EntityId
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated}
import com.evernym.verity.actor.{MappingAdded, RouteSet}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.{legacy, v1}
import com.evernym.verity.vault.WalletAPIParam
import com.typesafe.config.{Config, ConfigFactory}

/**
 * common/base code to store events and adding data to wallet store
 */
trait BasePersistentStore
  extends ActorSpec
    with ProvidesMockPlatform
    with BasicSpec {

  def appConfig: AppConfig

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

  def storeAgentRoute(agentDID: DID, actorTypeId: Int, address: EntityId)(implicit pp: PersistParam): Unit = {
    val routeStoreEntityId = RoutingAgentUtil.getBucketEntityId(agentDID)
    addEventsToPersistentStorage(s"$AGENT_ROUTE_STORE_REGION_ACTOR_NAME-$routeStoreEntityId",
      scala.collection.immutable.Seq(
        RouteSet(agentDID, actorTypeId, address)
      )
    )(pp.copy(encryptionKey = Option("4k3kejd845k4k3j")))
  }

  /**
   * key value mapper is the actor which gets updated when we setup agency agent
   * this method will store proper event to setup the agency DID in that actor
   */
  def storeAgencyDIDKeyValueMapping(agencyDID: DID)(implicit pp: PersistParam): Unit = {
    addEventsToPersistentStorage(s"$CLUSTER_SINGLETON_MANAGER-$KEY_VALUE_MAPPER_ACTOR_NAME",
      scala.collection.immutable.Seq(
        MappingAdded(AGENCY_DID_KEY, agencyDID)
      )
    )(pp.copy(encryptionKey = Option("krkifcjk4io5k4k4kl")))
  }

  /**
   * adds given events to given persistence id
   * @param events events to be stored in persistent storage
   */
  def addEventsToPersistentStorage(persistenceId: String,
                                   events: scala.collection.immutable.Seq[Any])(implicit pp: PersistParam): Unit = {
    if (pp.validateEventsCodeMapping) {
      events.foreach (evt => DefaultObjectCodeMapper.codeFromObject(evt))
    }
    val transformedEvents = events.map { evt =>
      val encKey = pp.encryptionKey.getOrElse(
        DefaultPersistenceEncryption.getEventEncryptionKeyWithoutWallet(pp.entityId, appConfig))
      val transformer = pp.transformerId match {
        case -1 => legacy.createLegacyEventTransformer(encKey)
        case 1  => v1.createPersistenceTransformerV1(encKey)
      }
      transformer.execute(evt)
    }
    persTestKit.persistForRecovery(persistenceId, transformedEvents)
  }

}

object PersistParam {
  /**
   * uses legacy event transformer
   * @param entityId
   * @param encryptionKey
   * @return
   */
  def legacy(entityId: EntityId, encryptionKey: Option[String] = None): PersistParam =
    PersistParam(entityId, -1, encryptionKey)

  /**
   * uses new event transformer
   * @param entityId
   * @param encryptionKey
   * @return
   */
  def default(entityId: EntityId, encryptionKey: Option[String] = None): PersistParam =
    PersistParam(entityId, 1, encryptionKey)
}

case class PersistParam(entityId: EntityId,
                        transformerId: Int,
                        encryptionKey: Option[String] = None,
                        validateEventsCodeMapping: Boolean = true)