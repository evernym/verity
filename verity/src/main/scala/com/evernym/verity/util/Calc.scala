package com.evernym.verity.util

import scala.annotation.tailrec

object Calc {

  val one: BigDecimal = 1
  val ten: BigDecimal = 10
  val hundred: BigDecimal = 100
  val thousand: BigDecimal = 1000
  val million: BigDecimal = thousand.pow(2)
  val billion: BigDecimal = thousand.pow(3)
  val trillion: BigDecimal = thousand.pow(4)
  val billionbillion: BigDecimal = billion.pow(2)

  def ext(x: BigDecimal*): BigDecimal = x.product
  def onein(x: BigDecimal*): BigDecimal = 1 / ext(x:_*)

  @tailrec
  final def fac(n: Int, a: BigInt = 1): BigInt = if (n == 0) a else fac(n-1, n*a)

  /**
    * Binomial Coefficient
    */
  def choose(n: Int, x: Int): BigDecimal = BigDecimal(fac(n)) / BigDecimal(fac(x) * fac(n-x))

  def exactly(tosses: Int, target: Int): BigDecimal = choose(tosses, target) / BigDecimal(2).pow(tosses)

  def atleast(tosses: Int, atleast: Int): BigDecimal = (atleast to tosses).map(exactly(tosses,_)).sum

}
