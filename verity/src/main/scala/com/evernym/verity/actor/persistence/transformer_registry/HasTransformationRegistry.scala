package com.evernym.verity.actor.persistence.transformer_registry

import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.transformations.transformers._
import com.evernym.verity.transformations.transformers.legacy._
import com.evernym.verity.transformations.transformers.v1._

trait HasTransformationRegistry extends LegacyTransformationRegistry {

  /**
   * symmetric encryption key to be used to encrypt/decrypt event/state
   * @return
   */
  def persistenceEncryptionKey: String
  def appConfig: AppConfig

  /**
   * implementations can override this for schema evolution
   */
  val schemaEvolTransformation: IdentityTransformer[Any] = new IdentityTransformer

  val persistentObjectMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper

  /**
   * new persistence transformer, optimized compared to legacy event/state transformers
   */
  lazy val persistenceTransformerV1: Any <=> PersistentMsg =
    createPersistenceTransformerV1(persistenceEncryptionKey, appConfig.getStringReq(SALT_EVENT_ENCRYPTION),
      persistentObjectMapper, schemaEvolTransformation)

  /**
   * transformer registry, map between a transformer id (Int) and corresponding composite transformer
   *
   * NOTE: once an entry is added to this registry and it is used in non local dev environment,
   * we should be very careful if at all we try to remove any such entry and be aware its impacts.
   */
  lazy val transformationRegistry: Map[Int, <=>[Any, _ <: Any]] = Map(
    LEGACY_EVENT_TRANSFORMATION_ID      -> legacyEventTransformer,
    LEGACY_STATE_TRANSFORMATION_ID      -> legacyStateTransformer,
    PERSISTENCE_TRANSFORMATION_ID_V1    -> persistenceTransformerV1
  )

  /**
   * lookup/searches an appropriate transformer based on given input
   *
   * @param id transformer id
   * @param typ optional, only used for legacy event/state transformers
   *            (as for both legacy event and state the transformation id was 0)
   *            for new transformers (id > 0), this parameter doesn't get used
   * @tparam T
   * @return a transformer
   */
  def lookupTransformer[T](id: Int, typ: Option[String]=None): Any <=> T = {
    val transformerId = (id, typ) match {
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, Some(LEGACY_PERSISTENT_OBJECT_TYPE_EVENT)) =>
        LEGACY_EVENT_TRANSFORMATION_ID
      case (LEGACY_PERSISTENCE_TRANSFORMATION_ID, Some(LEGACY_PERSISTENT_OBJECT_TYPE_STATE)) =>
        LEGACY_STATE_TRANSFORMATION_ID
      case (_, None)  => id      //for new transformers
      case (id, typ)  => throw new RuntimeException(s"transformation not supported for id '$id' and type '$typ'")
    }
    transformationRegistry.getOrElse(transformerId, throw new RuntimeException("transformation not found for id: " + id))
      .asInstanceOf[Any <=> T]
  }
}
