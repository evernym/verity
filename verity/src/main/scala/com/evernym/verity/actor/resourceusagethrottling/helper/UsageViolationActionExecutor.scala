package com.evernym.verity.actor.resourceusagethrottling.helper

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.cluster_singleton._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockCaller, BlockResourceForCaller}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{WarnCaller, WarnResourceForCaller}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants.SINGLETON_PARENT_PROXY
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.actor.resourceusagethrottling.helper.LogLevelValidator._
import com.evernym.verity.util.{SubnetUtilsExt, ThrottledLogger}
import com.evernym.verity.util.Util._
import com.typesafe.scalalogging.Logger


case class ViolatedRule(entityId: String, resourceName: String, bucketRule: BucketRule,
                        actualCount: Int, rulePath: String, bucketId: Int, actionPath: String)

trait InstructionDetailValidator {
  def instructionName: String
  def keyName: String
  def _required: Boolean
  def validate(keyPath: String, value: Any): Unit

  def isRequired: Boolean = _required

  val LOG_MSG_INSTRUCTION = "log-msg"
  val WARN_RESOURCE_INSTRUCTION = "warn-resource"
  val WARN_USER_INSTRUCTION = "warn-user"
  val BLOCK_RESOURCE_INSTRUCTION = "block-resource"
  val BLOCK_USER_INSTRUCTION = "block-user"
}


trait Instruction {
  def name: String
  def validators: Set[InstructionDetailValidator]

  def validateDetail(actionId: String, instructionDetail: InstructionDetail): Unit = {
    validators.foreach { v =>
      val value = instructionDetail.detail.get(v.keyName)
      value match {
        case Some(f) =>
          v.validate(s"$VIOLATION_ACTION.$actionId.${v.instructionName}.${v.keyName}", f)
        case None =>
          if (v.isRequired) {
            throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(
              s"$VIOLATION_ACTION.$actionId.${v.instructionName}.${v.keyName} is required key and not configured"))
          }
      }
    }
  }

  def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit

  def entityIdShouldBeTracked(entityId: String, actionDetail: InstructionDetail): Boolean = {
    val trackBy = actionDetail.detail.get("track-by")
    // Must only execute the instruction if the entityId matches the track-by type
    // 1. entityId must be "global" if track-by is "global"
    // 2. entityId must be an IP Address if track-by is "ip"
    // 3. entityId must be a user ID if track-by is "user"
    trackBy match {
      case Some("global") => entityId.equals("global")
      case Some("ip") => SubnetUtilsExt.isClassfulIpAddress(entityId)
      case Some("user") => isDID(entityId)
      case None => true
      case _ => false
    }
  }

  def buildTrackingData(violatedRule: ViolatedRule, actionDetail: InstructionDetail): (String, Int) = {
    val periodInSec = actionDetail.detail.getOrElse("period", "-1").toString.toInt
    val trackBy = actionDetail.detail.getOrElse("track-by", "ip").toString
    var trackByValue: String = violatedRule.entityId
    trackBy match {
      case "global" => trackByValue = trackBy
      case _ =>
    }
    (trackByValue, periodInSec)
  }
}


object LogLevelValidator extends InstructionDetailValidator {
  val instructionName: String = LOG_MSG_INSTRUCTION
  val keyName = "level"
  val _required: Boolean = false

  def validate(keyPath: String, value: Any): Unit = {
    value match {
      case "trace"| "debug" | "info" | "warn" | "error" =>
      case _ => throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(
        s"$keyPath has invalid value: $value"))
    }
  }
}

class PeriodValidator(val instructionName: String, val required: Boolean = true) extends InstructionDetailValidator {
  val keyName = "period"
  val _required: Boolean = required

  def validate(keyPath: String, value: Any): Unit = {
    try {
      value.toString.toInt match {
        case v if v >= -1 =>
        case _ => throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(
          s"$keyPath contains unsupported value: $value"))
      }
    } catch {
      case _: NumberFormatException =>
        throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(
          s"$keyPath contains non numeric value: $value"))
    }
  }
}

class TrackByValidator(val instructionName: String, val required: Boolean = true) extends InstructionDetailValidator {
  val keyName = "track-by"
  val _required: Boolean = required

  def validate(keyPath: String, value: Any): Unit = {
    value match {
      case "global" | "ip" | "user" =>
      case _ => throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option(
        s"$keyPath has invalid value: $value"))
    }
  }
}

object LogMsgInstruction extends Instruction {

  val logger: Logger = getLoggerByName("LogMsgInstruction")
  override val name: String = LOG_MSG_INSTRUCTION
  override val validators = Set(LogLevelValidator, new TrackByValidator(name, required = false))

  sealed trait LogMsgInstructionMessages
  case class ResourceUsageViolationWarning(entityID: String, resourceName: String) extends LogMsgInstructionMessages

  val throttledLogger = new ThrottledLogger[LogMsgInstructionMessages](logger)

  private def logMsg(level: String, id: ResourceUsageViolationWarning, msg: String): Unit = {
    level match {
      case "trace" => throttledLogger.trace(id, msg)
      case "debug" => throttledLogger.debug(id, msg)
      case "info" => throttledLogger.info(id, msg)
      case "warn" => throttledLogger.warn(id, msg)
      case _ => throttledLogger.error(id, msg)
    }
  }

  override def execute(violatedRule: ViolatedRule, instruction: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val id = ResourceUsageViolationWarning(violatedRule.entityId, violatedRule.resourceName)

    val msg = s"""msg="resource usage exceeded" entity_id="${violatedRule.entityId}"""" +
              s""" resource_name="${violatedRule.resourceName}" actual_count="${violatedRule.actualCount}"""" +
              s""" allowed_count="${violatedRule.bucketRule.allowedCount}" rule_path="${violatedRule.rulePath}"""" +
              s""" bucket_id="${violatedRule.bucketId}" action_path="${violatedRule.actionPath}"""" +
              s""" action_id="${violatedRule.bucketRule.violationActionId}""""

    instruction.detail.get("level") match {
      case Some(lvl: String) => logMsg(lvl, id, msg)
      case _ => logMsg("warn", id, msg)
    }
  }
}

class WarnResourceInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("WarnResourceInstruction")
  override val name: String = WARN_RESOURCE_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new TrackByValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (trackByValue, warnPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceWarningStatusMngr(WarnResourceForCaller(trackByValue,
      violatedRule.resourceName, warnPeriod=Option(warnPeriodInSec)))
  }

}

class WarnUserInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("WarnUserInstruction")
  override val name: String = WARN_USER_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new TrackByValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (trackByValue, warnPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceWarningStatusMngr(WarnCaller(trackByValue, warnPeriod=Option(warnPeriodInSec)))
  }
}

class BlockResourceInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("BlockResourceInstruction")
  override val name: String = BLOCK_RESOURCE_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new TrackByValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (trackByValue, blockPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceBlockingStatusMngr(BlockResourceForCaller(trackByValue,
      violatedRule.resourceName, blockPeriod=Option(blockPeriodInSec)))
  }

}

class BlockUserInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("BlockUserInstruction")
  override val name: String = BLOCK_USER_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new TrackByValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (trackByValue, blockPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceBlockingStatusMngr(BlockCaller(trackByValue, blockPeriod=Option(blockPeriodInSec)))
  }
}


trait UsageViolationActionExecutorBase {
  def singletonParentProxyActor: Option[ActorRef]
  def instructions: Set[Instruction]

  def buildInstructions(): Set[Instruction] = {
    val singletonDependentInstructions = singletonParentProxyActor match {
      case Some(spp) => Set (
        new WarnResourceInstruction(spp),
        new WarnUserInstruction(spp),
        new BlockResourceInstruction(spp),
        new BlockUserInstruction(spp)
      )
      case None => Set.empty
    }
    Set(LogMsgInstruction) ++ singletonDependentInstructions
  }

  def execute(actionId: String, violatedRule: ViolatedRule)(implicit sender: ActorRef): Unit = {
    ResourceUsageRuleHelper.resourceUsageRules.actionRules.get(actionId).foreach { ar =>
      ar.instructions.foreach { case (actionName, detail) =>
        instructions.find(_.name == actionName).foreach { i: Instruction =>
          if (i.entityIdShouldBeTracked(violatedRule.entityId, detail)) {
            i.execute(violatedRule, detail)
          }
        }
      }
    }
  }
}

class UsageViolationActionExecutorValidator extends UsageViolationActionExecutorBase {
  override lazy val singletonParentProxyActor: Option[ActorRef] = Some(ActorRef.noSender)
  override lazy val instructions: Set[Instruction] = buildInstructions()
}

class UsageViolationActionExecutor(val as: ActorSystem, appConfig: AppConfig) extends UsageViolationActionExecutorBase {
  //keep adding different supported action here
  override lazy val singletonParentProxyActor: Option[ActorRef] = Option(getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig))
  override lazy val instructions: Set[Instruction] = buildInstructions()

}
