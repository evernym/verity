package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, DEPRECATED_StateCodeMapper, ObjectCodeMapperBase}
import com.evernym.verity.actor.{DeprecatedEventMsg, DeprecatedStateMsg}

package object legacy {

  /**
   *
   * @param msg to be transformed
   * @param code optional unique code assigned/associated with the given msg
   *             (to be used later on during un-transformation to construct the given msg back)
   */
  case class TransParam[+T](msg: T, code: Option[Int]=None) {
    def codeReq: Int = code.getOrElse(throw new RuntimeException("code required but not supplied"))
  }

  /**
   * this id (0) was used for both 'event' and 'state' transformations
   */
  final val LEGACY_PERSISTENCE_TRANSFORMATION_ID = 0

  /**
   *
   * @param persistenceEncryptionKey encryption key
   * @return
   */
  def createLegacyEventTransformer(persistenceEncryptionKey: String,
                                   objectCodeMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper): Any <=> DeprecatedEventMsg = {

    val legacyEncryptor = new LegacyAESEncryptionTransformer(persistenceEncryptionKey)
    val legacyPersistenceTransformer = new LegacyEventPersistenceTransformer(LEGACY_PERSISTENCE_TRANSFORMATION_ID)

    new LegacyProtoBufTransformer(objectCodeMapper) andThen
      LegacyJavaSerializationTransformer andThen
      legacyEncryptor andThen
      legacyPersistenceTransformer
  }

  /**
   *
   * @param persistenceEncryptionKey encryption key
   * @return
   */
  def createLegacyStateTransformer(persistenceEncryptionKey: String,
                                   objectCodeMapper: ObjectCodeMapperBase = DEPRECATED_StateCodeMapper): Any <=> DeprecatedStateMsg = {

    val legacyEncryptor = new LegacyAESEncryptionTransformer(persistenceEncryptionKey)
    val legacyPersistenceTransformer = new LegacyStatePersistenceTransformer(LEGACY_PERSISTENCE_TRANSFORMATION_ID)

      new LegacyProtoBufTransformer(objectCodeMapper) andThen
      LegacyJavaSerializationTransformer andThen
      legacyEncryptor andThen
      legacyPersistenceTransformer
  }

}
