package com.evernym.verity.transformations

trait Transformer[P, R] {

  def apply(input: P): R = execute(input)

  /**
    * executes the transformation
    * @return
    */
  def execute: P => R

  /**
    * reverses the transformation; this is the inverse of [[execute]]
    *
    * @return
    */
  def undo: R => P

  def andThen[A](t: Transformer[R, A]): Transformer[P, A] = {
    BasicTransformer(execute andThen t.execute, undo compose t.undo)
  }

}


case class BasicTransformer[P, R](execute: P => R, undo: R => P) extends Transformer[P, R]
