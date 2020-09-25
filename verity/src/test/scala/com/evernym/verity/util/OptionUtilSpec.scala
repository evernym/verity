package com.evernym.verity.util

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.OptionUtil._

class OptionUtilSpec extends BasicSpec {
  "emptyOption" - {
    "should convert" - {
      "null to None" in {
        emptyOption(null, false) should be(None)
      }

      "empty types to None" in {
        emptyOption(Seq()) shouldBe None
        emptyOption(Seq(1)) should not be empty
        emptyOption(Seq("1")) should not be empty

        emptyOption("") shouldBe None
        emptyOption("HI") should not be empty

        emptyOption(Array()) shouldBe None
        emptyOption(Array(1)) should not be empty
        emptyOption(Array("1")) should not be empty
      }

      "non-null to None if empty" in {
        emptyOption("SDFSDF", true) should be(None)
        emptyOption("SDFSDF", false) should be(Some("SDFSDF"))
      }
    }
  }

  "blankOption" - {
    "should convert" - {
      "null to None" in {
        blankOption(null) should be(None)
      }
    }
  }

  "blankFlattenOption" - {
    "should leave" - {
      "None as None" in {
        blankFlattenOption(None) should be(None)
      }

      "non-empty strings alone" in {
        blankFlattenOption(Some("        sdf \n")) should be(Some("        sdf \n"))
      }
    }
    "should convert" - {
      "null to None" in {
        blankFlattenOption(Some(null)) should be(None)
        blankFlattenOption(null) should be(None)
      }

      "\"\" to None" in {
        blankFlattenOption(Some("")) should be(None)
      }

      "whitespace to None" in {
        blankFlattenOption(Some("            ")) should be(None)
        blankFlattenOption(Some("       \t   \n")) should be(None)
        blankFlattenOption(Some(" " * 4444)) should be(None)
        blankFlattenOption(Some(s"${'\u0013'}")) should be(None)
      }
    }
  }

  "fullTupleToOption" - {
    "should map" - {
      "null to None" in {
        fullTupleToOption(null) shouldBe None
        fullTupleToOption(Tuple1(null)) shouldBe None
        fullTupleToOption((null, null)) shouldBe None
        fullTupleToOption((null, "test")) shouldBe None
        fullTupleToOption(("test", null)) shouldBe None
      }

      "full Tuple to Some" in {
        fullTupleToOption(("test", 1)) should not be None
      }
    }
  }

  "allOrNone" - {
    "should allow complete list" in {
      allOrNone(Seq(Some("A"))) shouldBe Some(Seq("A"))
      allOrNone(Seq(Some("A"), Some("B"))) shouldBe Some(Seq("A", "B"))
      allOrNone(Seq(Some("A"), Some("B"), Some("C"))) shouldBe Some(Seq("A", "B", "C"))
      allOrNone(Some("A"), Some("B"), Some("C")) shouldBe Some("A", "B", "C")
    }

    "should be None if any are None" in {
      allOrNone(Seq(None)) shouldBe None
      allOrNone(Seq(Some("A"), None)) shouldBe None
      allOrNone(Seq(Some("A"), None, Some("C"))) shouldBe None
    }
  }

  "falseToNone" - {
    "works with true, false and null" in {
      falseToNone(true).value shouldBe true
      falseToNone(false) shouldBe None

      val t: Object = null
      falseToNone(t.asInstanceOf[Boolean]) shouldBe None

      //flatmap use case
      Some(false).flatMap(falseToNone) shouldBe None
      Some(true).flatMap(falseToNone).value shouldBe true
    }
  }
}
