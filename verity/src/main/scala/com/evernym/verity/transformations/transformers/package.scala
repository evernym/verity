package com.evernym.verity.transformations

import com.evernym.verity.actor.PersistentData

package object transformers {

  /** Syntactic sugar for Transformer types
    *
    * This can be used in type declarations:
    * {{{
    * val myTransformer: Int <=> String
    * }}}
    *
    * This can be used in class definitions (with parentheses):
    * {{{
    * class MyTransformer extends (Int <=> String) { ... }
    * }}}
    */
  type <=>[A, B] = Transformer[A, B]

  type TransformationId = Int

  /**
   * new optimized event/state transformer id
   */
  final val PERSISTENCE_TRANSFORMATION_ID = 1

  /**
   *
   * @param persistenceEncryptionKey encryption key
   * @param schemaEvolutionTransformation transformation to convert domain object
   *                                      to an object to be serialized or vice versa
   * @return
   */
  def createPersistenceTransformer(persistenceEncryptionKey: String,
                                   schemaEvolutionTransformation: Any <=> Any
                                  ): Any <=> PersistentData = {

    schemaEvolutionTransformation andThen
      DefaultProtoBufTransformer andThen
      new AESEncryptionTransformer(persistenceEncryptionKey) andThen
      new PersistenceTransformer(PERSISTENCE_TRANSFORMATION_ID)
  }
}