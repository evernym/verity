package com.evernym.verity.util

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.ObjectSizeUtil._

class ObjectSizeUtilSpec
  extends BasicSpec  {

  "ClassLayout" - {
    "when asked to calculate size of strings" - {
      "should respond with accurate size" in {
        calcSizeInBytes("a") shouldBe 3
        calcSizeInBytes("an") shouldBe 3
        calcSizeInBytes("the") shouldBe 4
        calcSizeInBytes("small string") shouldBe 13
        calcSizeInBytes("this is a long sentence") shouldBe 24
      }
    }

    "when asked to calculate size of objects" - {
      "should respond with accurate size" in {
        calcSizeInBytes(SizeCheck(1, "test", aBoolean = false)) shouldBe 8
        calcSizeInBytes(SizeCheck(1, "test", aBoolean = true)) shouldBe 8
        calcSizeInBytes(SizeCheck(21, "test", aBoolean = false)) shouldBe 8
        calcSizeInBytes(SizeCheck(100, "test", aBoolean = true)) shouldBe 9
        calcSizeInBytes(SizeCheck(100, "test string", aBoolean = false)) shouldBe 16
      }
    }
  }


}

case class SizeCheck(anInt: Int, aString: String, aBoolean: Boolean)