package com.evernym.verity.util

import com.evernym.verity.util.CalUtil._
import com.evernym.verity.testkit.BasicSpec


class CalUtilSpec extends BasicSpec {
  "percentDifference" - {
    "should work with floats" in {
      percentDifference(1.2f, 2.3f) shouldBe -0.6285713740757534
    }
    "should work with doubles" in {
      percentDifference(1.2d, 2.3d) shouldBe -0.6285714285714284
    }
    "should work with integers" in {
      percentDifference(1, 2) shouldBe -0.6666666666666666
    }
    "should work with equal values" in {
      percentDifference(1, 1) shouldBe -0.0d
    }
    "should work with MAX values" in {
      percentDifference(Integer.MAX_VALUE, Integer.MAX_VALUE) shouldBe 0d
    }
  }

}
