package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}

package object v1 {
  /**
   * new optimized event/state transformer id version 1
   */
  final val PERSISTENCE_TRANSFORMATION_ID_V1 = 1

  /**
   *
   * @param persistenceEncryptionKey encryption key
   * @param schemaEvolutionTransformation transformation to convert domain object
   *                                      to an object to be serialized or vice versa
   * @return
   */
  def createPersistenceTransformerV1(persistenceEncryptionKey: String,
                                     eventEncryptionSalt: String,
                                     objectCodeMapper: ObjectCodeMapperBase = DefaultObjectCodeMapper,
                                     schemaEvolutionTransformation: Any <=> Any = new IdentityTransformer
                                    ): Any <=> PersistentMsg = {
    schemaEvolutionTransformation andThen
      new ProtoBufTransformerV1(objectCodeMapper) andThen
      new AESEncryptionTransformerV1(persistenceEncryptionKey, eventEncryptionSalt) andThen
      new PersistenceTransformer(PERSISTENCE_TRANSFORMATION_ID_V1)
  }
}
