package com.evernym.verity.transformations

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

}
