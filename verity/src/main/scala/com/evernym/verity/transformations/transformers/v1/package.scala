package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.PersistentData

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
                                     schemaEvolutionTransformation: Any <=> Any
                                    ): Any <=> PersistentData = {

    schemaEvolutionTransformation andThen
      DefaultProtoBufTransformerV1 andThen
      new AESEncryptionTransformerV1(persistenceEncryptionKey) andThen
      new PersistenceTransformer(PERSISTENCE_TRANSFORMATION_ID_V1)
  }
}
