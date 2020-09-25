package com.evernym.verity.util

import com.evernym.verity.util.ExceptionUtil._
import com.evernym.verity.testkit.BasicSpec


class ExceptionUtilSpec extends BasicSpec {

  "allow has no effect if now exception is thrown" in {
    var t = "123"
    allow[RuntimeException]{
      t = "sdf"
      ()
    }

    t shouldBe "sdf"
  }

  "allow dose not throw allowed exception" in {
    var t = "123"
    allow[RuntimeException]{
      t = "sdf"
      throw new RuntimeException("HELP")
    }

    t shouldBe "sdf"
  }

  "allow dose throw not allowed exception" in {
    var t = "123"
    intercept[Exception] {
      allow[RuntimeException]{
        t = "sdf"
        throw new Exception("HELP")
      }
    }
  }

  "allowCause has no effect if now exception is thrown" in {
    var t = "123"
    allowCause[RuntimeException]{
      t = "sdf"
      ()
    }

    t shouldBe "sdf"
  }

  "allowCause dose not throw allowed exception" in {
    var t = "123"
    allowCause[RuntimeException]{
      t = "sdf"
      throw new Exception("HELP", new RuntimeException("HELP ME"))
    }

    t shouldBe "sdf"
  }

  "allowCause dose throw not allowed exception" in {
    var t = "123"
    intercept[Exception] {
      allowCause[RuntimeException]{
        t = "sdf"
        throw new Exception("HELP")
      }
    }

    intercept[Exception] {
      allowCause[IllegalArgumentException]{
        t = "sdf"
        throw new Exception("HELP", new UnsupportedOperationException("HELP"))
      }
    }
  }

  "allowCauseConditionally dose not throw allowed exception" in {
    var t = "123"
    allowCauseConditionally[IllegalArgumentException](_.getMessage == "HELP"){
      t = "sdf"
      throw new Exception("TEST ME", new IllegalArgumentException("HELP"))
    }

    t shouldBe "sdf"
  }

  "allowCauseConditionally dose throw not allowed exception" in {
    var t = "123"
    intercept[Exception] {
      allowCauseConditionally[IllegalArgumentException](_.getMessage == "HELP"){
        t = "sdf"
        throw new Exception("TEST ME", new IllegalArgumentException("NOT WHAT"))
      }
    }
  }

}
