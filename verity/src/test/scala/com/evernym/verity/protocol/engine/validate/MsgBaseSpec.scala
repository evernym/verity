package com.evernym.verity.protocol.engine.validate

import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkRequired, checkValidDID}
import com.evernym.verity.protocol.engine.{InvalidFieldValueProtocolEngineException, MissingReqFieldProtocolEngineException, MsgBase, ProtocolEngineException}
import com.evernym.verity.testkit.BasicSpec

class MsgBaseSpec extends BasicSpec {

  class TestException(statusMsg: String)
    extends ProtocolEngineException(statusMsg)

  object MsgBaseTest extends MsgBase {
    def apply(pass: Boolean): MsgBaseTest = new MsgBaseTest(pass)
  }

  class MsgBaseTest(pass: Boolean = true) extends MsgBase {
    override def validate(): Unit = {
      if (!pass)
        throw new TestException("failed")
    }
  }

  class TestClass {}

  val nullString: String = null
  val nullList: List[String] = null
  val nullMap: Map[String, String] = null
  val nullOther: TestClass = null

  "ValidateHelper" - {
    "checkRequired string" - {
      "allowEmpty = false" - {
        val allowEmpty = false

        "valid should pass" in {
          checkRequired("attrName1", "value", allowEmpty)
        }

        "empty string should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", "", allowEmpty)
          }
        }

        "null string should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullString, allowEmpty)
          }
        }
      }

      "allowEmpty = true" - {
        val allowEmpty = true

        "valid should pass" in {
          checkRequired("attrName1", "value", allowEmpty)
        }

        "empty string should pass" in {
          checkRequired("attrName1", "value", allowEmpty)
        }

        "null string should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullString, allowEmpty)
          }
        }
      }
    }

    "checkRequired List" - {
      "allowEmpty = false" - {
        val allowEmpty = false

        "valid should pass" in {
          checkRequired("attrName1", List("a", "b"), allowEmpty)
        }

        "empty list should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", List(), allowEmpty)
          }
        }

        "null list should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullList, allowEmpty)
          }
        }

        "list with null value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", List("a", nullString), allowEmpty)
          }
        }

        "list with empty value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", List("a", ""), allowEmpty)
          }
        }
      }
      "allowEmpty = true" - {
        val allowEmpty = true

        "valid should pass" in {
          checkRequired("attrName1", List("a", "b"), allowEmpty)
        }

        "empty list should pass" in {
          checkRequired("attrName1", List(), allowEmpty)
        }

        "null list should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullList, allowEmpty)
          }
        }

        "list with null value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", List("a", nullString), allowEmpty)
          }
        }

        "list with empty value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", List("a", ""), allowEmpty)
          }
        }
      }
    }

    "checkRequired Map[String, String]" - {
      "allowEmpty = false" - {
        val allowEmpty = false

        "valid should pass" in {
          checkRequired("attrName1", Map("a" -> "va", "b" -> "vb"), allowEmpty)
        }

        "empty map should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map(), allowEmpty)
          }
        }

        "null map should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullMap, allowEmpty)
          }
        }

        "map with empty key should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", "" -> "vb"), allowEmpty)
          }
        }

        "map with null key should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", nullString -> "vb"), allowEmpty)
          }
        }

        "map with empty value should pass" in {
          checkRequired("attrName1", Map("a" -> "va", "b" -> ""), allowEmpty)
        }

        "map with null value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", "b" -> nullString), allowEmpty)
          }
        }
      }

      "allowEmpty = true" - {
        val allowEmpty = true

        "valid should pass" in {
          checkRequired("attrName1", Map("a" -> "va", "b" -> "vb"), allowEmpty)
        }

        "empty map should pass" in {
          checkRequired("attrName1", Map(), allowEmpty)
        }

        "null map should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullMap, allowEmpty)
          }
        }

        "map with empty key should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", "" -> "vb"), allowEmpty)
          }
        }

        "map with null key should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", nullString -> "vb"), allowEmpty)
          }
        }

        "map with empty value should pass" in {
          checkRequired("attrName1", Map("a" -> "va", "b" -> ""), allowEmpty)
        }

        "map with null value should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", Map("a" -> "va", "b" -> nullString), allowEmpty)
          }
        }
      }
    }

    "checkRequired MsgBase class" - {
      "allowEmpty = false" - {
        val allowEmpty = false

        "if validation of inner object passes should pass" in {
          checkRequired("attrName1", MsgBaseTest(true), allowEmpty)
        }

        "if validation of inner object fails should throw its exception" in {
          assertThrows[TestException] {
            checkRequired("attrName1", MsgBaseTest(false), allowEmpty)
          }
        }
      }
      "allowEmpty = true" - {
        val allowEmpty = true

        "if validation of inner object passes should pass" in {
          checkRequired("attrName1", MsgBaseTest(true), allowEmpty)
        }

        "if validation of inner object fails should throw its exception" in {
          assertThrows[TestException] {
            checkRequired("attrName1", MsgBaseTest(false), allowEmpty)
          }
        }
      }
    }

    "checkRequired other" - {
      "allowEmpty = false" - {
        val allowEmpty = false

        "valid should pass" in {
          checkRequired("attrName1", new TestClass, allowEmpty)
        }

        "null should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullOther, allowEmpty)
          }
        }
      }

      "allowEmpty = true" - {
        val allowEmpty = true

        "valid should pass" in {
          checkRequired("attrName1", new TestClass, allowEmpty)
        }

        "null should throw MissingReqFieldProtocolEngineException" in {
          assertThrows[MissingReqFieldProtocolEngineException] {
            checkRequired("attrName1", nullOther, allowEmpty)
          }
        }
      }
    }

    "checkValidDID" - {
      "valid should pass" in {
        checkValidDID("attrName", "V4SGRU86Z58d6TV7PBUe6f")
      }

      "too long string should fail" in {
        assertThrows[InvalidFieldValueProtocolEngineException] {
          checkValidDID(
            "attrName",
            "dsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafddsafa923ed8nfgsafd"
          )
        }
      }

      "if not base58 should fail" in {
        assertThrows[InvalidFieldValueProtocolEngineException] {
          checkValidDID("attrName", "V4SGRU86Z58d6_V7PBUe6f")
        }
      }

      "too long decoded base58 should fail" in {
        assertThrows[InvalidFieldValueProtocolEngineException] {
          checkValidDID("attrName", "V4SGRU86Z58d6TV7PBUe6fA")
        }
      }
    }
  }
}
