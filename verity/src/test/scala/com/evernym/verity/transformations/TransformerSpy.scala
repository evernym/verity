package com.evernym.verity.transformations

import scala.collection.mutable

case class TransformerSpy[P,R](inner: Transformer[P,R]) extends Transformer[P,R] {

  val spyExecs: SpyQueue[P,R] = new SpyQueue
  val spyUndos: SpyQueue[R,P] = new SpyQueue

  override def execute: P => R = spyExecs spy inner.execute

  override def undo: R => P = spyUndos spy inner.undo

}

class SpyQueue[A,B] extends mutable.Queue[(A,B)] {
  /** takes a function and returns a function of the same type that
    * executes the function and records the inputs and outputs
    */
  def spy(f: A => B): A => B = { a =>
    val r = f(a)
    super.enqueue(a -> r)
    r
  }
}