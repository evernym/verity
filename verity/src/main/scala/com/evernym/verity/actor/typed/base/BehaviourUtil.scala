package com.evernym.verity.actor.typed.base

import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EffectBuilder}
import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.transformations.transformers.v1.{PERSISTENCE_TRANSFORMATION_ID_V1, createPersistenceTransformerV1}
import com.evernym.verity.transformations.transformers.{<=>, IdentityTransformer}
import com.typesafe.scalalogging.Logger

case class BehaviourUtil(persId: PersistenceId,
                         encryptionKey: String,
                         objectCodeMapper: ObjectCodeMapperBase) {

  val logger: Logger = getLoggerByClass(getClass)

  //takes the given event, applies persistence transformations (serialization, encryption etc)
  // before it goes to akka persistence layer
  def persist[E, S](event: E): EffectBuilder[PersistentMsg, S] = {
    Effect.persist(eventTransformer.execute(event))
  }

  //during recovery, checks if the recovered event is 'PersistentMsg'
  // and then un-transforms it (decryption, deserialization etc)
  def eventHandlerWrapper[E, S](eventHandler: (S, E) => S): (S, E) => S = {
    case (state, pm: PersistentMsg) =>
      val untransformedEvent =
        lookupTransformer(pm.transformationId)
          .undo(pm)
          .asInstanceOf[E]
      eventHandler(state, untransformedEvent)
  }

  /**
   * lookup/searches an appropriate transformer based on given input
   *
   * @param id  transformer id
   * @param typ optional, only used for legacy event/state transformers
   *            (as for both legacy event and state the transformation id was 0)
   *            for new transformers (id > 0), this parameter doesn't get used
   * @tparam T
   * @return a transformer
   */
  def lookupTransformer[T](id: Int, typ: Option[String] = None): Any <=> T = {
    val transformerId = (id, typ) match {
      case (_, None) => id //for new transformers
      case (id, typ) => throw new RuntimeException(s"transformation not supported for id '$id' and type '$typ'")
    }
    transformationRegistry.getOrElse(transformerId, throw new RuntimeException("transformation not found for id: " + id))
      .asInstanceOf[Any <=> T]
  }

  /**
   * implementations can override this for schema evolution
   */
  private val schemaEvolTransformation: IdentityTransformer[Any] = new IdentityTransformer

  private val persistentObjectMapper: ObjectCodeMapperBase = objectCodeMapper

  /**
   * transformer registry, map between a transformer id (Int) and corresponding composite transformer
   *
   * NOTE: once an entry is added to this registry and it is used in non local dev environment,
   * we should be very careful if at all we try to remove any such entry and be aware its impacts.
   */
  private lazy val transformationRegistry: Map[Int, <=>[Any, _ <: Any]] = Map(
    PERSISTENCE_TRANSFORMATION_ID_V1 -> persistenceTransformerV1
  )

  /**
   * new persistence transformer, optimized compared to legacy event/state transformers
   */
  private lazy val persistenceTransformerV1: Any <=> PersistentMsg =
    createPersistenceTransformerV1(encryptionKey, persistentObjectMapper, schemaEvolTransformation)

  private lazy val eventTransformer: Any <=> PersistentMsg = persistenceTransformerV1

}
