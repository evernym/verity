package com.evernym.verity.transformations.transformers

import com.evernym.verity.transformations.Transformer

/**
 * identity transformer
 *
 * @tparam A
 */
class IdentityTransformer[A] extends Transformer[A, A] {

  override val execute: A => A = { a => a }

  override val undo: A => A = { a => a}

}
