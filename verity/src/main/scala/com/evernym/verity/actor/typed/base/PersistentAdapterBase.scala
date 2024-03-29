package com.evernym.verity.actor.typed.base

import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.transformations.transformers.{<=>, IdentityTransformer}
import com.evernym.verity.transformations.transformers.v1.{PERSISTENCE_TRANSFORMATION_ID_V1, createPersistenceTransformerV1}

trait PersistentAdapterBase {
  def encryptionKey: String
  def objectCodeMapper: ObjectCodeMapperBase
  def eventEncryptionSalt: String

  protected lazy val persistenceTransformer: Any <=> PersistentMsg = persistenceTransformerV1
  /**
   * lookup/searches an appropriate transformer based on given input
   *
   * @param id  transformer id
   * @tparam T
   * @return a transformer
   */
  protected def lookupTransformer[T](id: Int): Any <=> T = {
    transformationRegistry.getOrElse(id, throw new RuntimeException("transformation not found for id: " + id))
      .asInstanceOf[Any <=> T]
  }

  /**
   * transformer registry, map between a transformer id (Int) and corresponding composite transformer
   *
   * NOTE: once an entry is added to this registry and it is used in "non local dev environment",
   * we should be very careful if at all we try to remove any such entry and be aware its impacts.
   */
  private lazy val transformationRegistry: Map[Int, <=>[Any, _ <: Any]] = Map(
    PERSISTENCE_TRANSFORMATION_ID_V1 -> persistenceTransformerV1
  )

  /**
   * persistence transformer, optimized compared to legacy event/state transformers
   */
  private lazy val persistenceTransformerV1: Any <=> PersistentMsg =
    createPersistenceTransformerV1(encryptionKey, eventEncryptionSalt, persistentObjectMapper, schemaEvolTransformation)


  //maps events to a unique code to be used during recovery to
  // deserialize the serialized event back to native object
  private val persistentObjectMapper: ObjectCodeMapperBase = objectCodeMapper

  private val schemaEvolTransformation: IdentityTransformer[Any] = new IdentityTransformer
}
