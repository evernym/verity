package com.evernym.verity.actor.persistence.transformer_registry

import com.evernym.verity.actor.PersistentData
import com.evernym.verity.transformations.transformers._
import com.evernym.verity.transformations.transformers.legacy.LEGACY_PERSISTENCE_TRANSFORMATION_ID


trait HasTransformationRegistry extends LegacyTransformationRegistry {

  /**
   * symmetric encryption key to be used to encrypt/decrypt event/state
   * @return
   */
  def persistenceEncryptionKey: String

  /**
   * implementations can override this for schema evolution
   */
  val schemaEvolTransformation: IdentityTransformer[Any] = new IdentityTransformer

  /**
   * new persistence transformer, optimized compared to legacy event/state transformers
   * TODO: 'CodeMsgExtractor' code needs to be reviewed and finalized before getting used
   */
  lazy val persistenceTransformer: Any <=> PersistentData =
    createPersistenceTransformer(persistenceEncryptionKey, schemaEvolTransformation)

  /**
   * transformer registry, map between a transformer id (Int) and corresponding composite transformer
   */
  lazy val transformationRegistry: Map[Int, <=>[Any, _ <: Any]] = Map(
    LEGACY_EVENT_TRANSFORMATION_ID      -> legacyEventTransformer,
    LEGACY_STATE_TRANSFORMATION_ID      -> legacyStateTransformer,

    //TODO: uncomment below entry when we want to start using this new optimized transformer
    //PERSISTENCE_TRANSFORMATION_ID_ONE   -> persistenceTransformer
  )

  /**
   * lookup/searches an appropriate transformer based on given input
   *
   * @param id transformer id
   * @param typ optional, only used for legacy event/state transformers (id=0)
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
      case (id, typ)     => throw new RuntimeException(s"transformation not supported for id '$id' and type '$typ'")
    }
    transformationRegistry.getOrElse(transformerId, throw new RuntimeException("transformation not found for id: " + id))
      .asInstanceOf[Any <=> T]
  }
}
