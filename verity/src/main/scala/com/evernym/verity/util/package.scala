package com.evernym.verity

import scala.language.implicitConversions
import scala.util.matching

package object util {

  trait ArrowAssocType {
    def unapply[A, B](pair: (A, B)): Option[(A, B)] =
      Some(pair)
  }
  object -> extends ArrowAssocType
  object → extends ArrowAssocType

  //simple union type
  sealed trait ∪[A, B]

  object ∪ {
    implicit def a2Or[A,B](a: A): ∪[A, B] = new ∪[A, B] {}
    implicit def b2Or[A,B](b: B): ∪[A, B] = new ∪[A, B] {}
  }

  implicit class RegexHelp(sc: StringContext) {
    def r = new matching.Regex(sc.parts.mkString, sc.parts.tail.map(_ => "x"): _*)
  }

  implicit def intTimes(i: Int): Times = new Times(i)

  class Times(i: Int) {
    def times[T](block: => T): IndexedSeq[T] = {
      val fn = () => block
      (1 to i) map (_ => fn())
    }
  }

}
