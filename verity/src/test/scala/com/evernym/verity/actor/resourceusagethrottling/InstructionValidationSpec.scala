package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.util2.Exceptions.InvalidValueException
import com.evernym.verity.actor.resourceusagethrottling.helper.LogLevelValidator.WARN_RESOURCE_INSTRUCTION
import com.evernym.verity.actor.resourceusagethrottling.helper.{EntityTypesValidator, LogLevelValidator, PeriodValidator}
import com.evernym.verity.config.ConfigConstants.VIOLATION_ACTION
import com.evernym.verity.testkit.BasicSpec

class InstructionValidationSpec
  extends BasicSpec {

  "LogLevelValidator" - {

    "when asked to validate correct level" - {
      "should be successful" in {
        val keyPath = s"$VIOLATION_ACTION.50.log-msg.level"
        List("trace", "debug", "info", "warn", "error").foreach { level =>
          LogLevelValidator.validate(keyPath, level)
        }
      }
    }

    "when asked to validate invalid level" - {
      "should throw exception" in {
        val keyPath = s"$VIOLATION_ACTION.50.log-msg.level"
        List("verbose").foreach { level =>
          val ex = intercept[InvalidValueException] {
            LogLevelValidator.validate(keyPath, level)
          }
          ex.getMessage shouldBe s"$keyPath has invalid value: $level"
        }
      }
    }
  }

  "PeriodValidator" - {

    "when asked to validate correct warn/block period" - {
      "should be successful" in {
        val periodValidator = new PeriodValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.period"
        List(-1, 0, 50).foreach { period =>
          periodValidator.validate(keyPath, period)
        }
      }
    }

    "when asked to validate invalid warn/block period" - {
      "should throw exception" in {
        val periodValidator = new PeriodValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.period"
        List(-2, -10, -50).foreach { period =>
          val ex = intercept[InvalidValueException] {
            periodValidator.validate(keyPath, period)
          }
          ex.getMessage shouldBe s"$keyPath contains unsupported value: $period"
        }

        List("abc").foreach { period =>
          val ex = intercept[InvalidValueException] {
            periodValidator.validate(keyPath, period)
          }
          ex.getMessage shouldBe s"$keyPath contains non numeric value: $period"
        }
      }
    }

  }

  "EntityTypesValidator" - {

    "when asked to validate correct entity-types" - {
      "should be successful" in {
        val entityTypesValidator = new EntityTypesValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.entity-types"
        List("global", "ip", "user", "user-owner", "user-counterparty", "ip,user-owner,user-counterparty").foreach { entityTypes =>
          entityTypesValidator.validate(keyPath, entityTypes)
        }
      }
    }

    "when asked to validate invalid entity-types" - {
      "should throw exception" in {
        val entityTypesValidator = new EntityTypesValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.entity-types"
        List(100, true).foreach { entityTypes =>
          val ex = intercept[InvalidValueException] {
            entityTypesValidator.validate(keyPath, entityTypes)
          }
          ex.getMessage shouldBe s"$keyPath contains unsupported value: $entityTypes"
        }
      }
    }

    "when asked to validate entity-types containing no elements" - {
      "should throw exception" in {
        val entityTypesValidator = new EntityTypesValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.entity-types"
        List(",").foreach { entityTypes =>
          val ex = intercept[InvalidValueException] {
            entityTypesValidator.validate(keyPath, entityTypes)
          }
          ex.getMessage shouldBe s"$keyPath value treated as a comma-separated values list contains no values: $entityTypes"
        }
      }
    }

    "when asked to validate entity-types containing invalid elements" - {
      "should throw exception" in {
        val entityTypesValidator = new EntityTypesValidator(WARN_RESOURCE_INSTRUCTION)
        val keyPath = s"$VIOLATION_ACTION.50.warn-resource.entity-types"

        List("", "invalid").foreach { entityTypes =>
          val ex = intercept[InvalidValueException] {
            entityTypesValidator.validate(keyPath, entityTypes)
          }
          ex.getMessage shouldBe s"$keyPath value contains unsupported element: $entityTypes"
        }

        {
          val ex = intercept[InvalidValueException] {
            entityTypesValidator.validate(keyPath, "ip,invalid")
          }
          ex.getMessage shouldBe s"$keyPath value contains unsupported element: invalid"
        }
      }
    }

  }

}
