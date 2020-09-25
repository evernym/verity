package com.evernym.verity

import com.evernym.verity.testkit.BasicSpec


class TypeClassSpec extends BasicSpec {

  trait Default[A] { def apply(): A }

  trait Platform
  case class ProdPlatform(foo: String) extends Platform
  case class TestPlatform(bar: Int) extends Platform

  trait LowPriorityStageInstances {
    implicit object testPlatformDefault extends Default[TestPlatform] {
      def apply() = TestPlatform(13)
    }
  }

  object Platform extends LowPriorityStageInstances {
    implicit object platformDefault extends Default[Platform] {
      def apply() = ProdPlatform("foo")
    }
  }

  def getPlatform[T <: Platform: Default](key: String): T =
    implicitly[Default[T]].apply()

  "test" in {
    getPlatform("") shouldBe ProdPlatform("foo")
    getPlatform[TestPlatform]("") shouldBe TestPlatform(13)
  }
}
