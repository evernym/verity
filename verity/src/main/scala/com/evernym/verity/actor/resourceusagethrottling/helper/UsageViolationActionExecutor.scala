package com.evernym.verity.actor.resourceusagethrottling.helper

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.Exceptions.{InvalidValueException, MissingReqFieldException}
import com.evernym.verity.actor.cluster_singleton._
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{BlockCaller, BlockResourceForCaller}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{WarnCaller, WarnResourceForCaller}
import com.evernym.verity.actor.resourceusagethrottling.{ENTITY_ID_GLOBAL, EntityId}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants.SINGLETON_PARENT_PROXY
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.actor.resourceusagethrottling.helper.LogLevelValidator._
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil.{isUserIdCounterparty, isUserIdOwner}
import com.evernym.verity.logging.ThrottledLogger
import com.evernym.verity.util.SubnetUtilsExt
import com.evernym.verity.util.Util._
import com.typesafe.scalalogging.Logger


class UsageViolationActionExecutor(val as: ActorSystem, appConfig: AppConfig)
  extends UsageViolationActionExecutorBase {
  //keep adding different supported action here
  override lazy val singletonParentProxyActor: Option[ActorRef] = Option(getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig))
  override lazy val instructions: Set[Instruction] = buildInstructions()
}


case class ViolatedRule(entityId: String, resourceName: String, rulePath: String,
                        bucketId: Int, bucketRule: BucketRule, actualCount: Int) {

  val actionPath: String = VIOLATION_ACTION
}

trait InstructionDetailValidator {
  def instructionName: String
  def keyName: String
  def _required: Boolean
  def validate(keyPath: String, value: Any): Unit

  def isRequired: Boolean = _required

  val LOG_MSG_INSTRUCTION = "log-msg"
  val WARN_RESOURCE_INSTRUCTION = "warn-resource"
  val WARN_ENTITY_INSTRUCTION = "warn-entity"
  val BLOCK_RESOURCE_INSTRUCTION = "block-resource"
  val BLOCK_ENTITY_INSTRUCTION = "block-entity"

  val ENTITY_TYPES_FILTER = "entity-types"
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
            throw new MissingReqFieldException(Option(
              s"$VIOLATION_ACTION.$actionId.${v.instructionName}.${v.keyName} is required key and not configured"))
          }
      }
    }
  }

  def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit

  def isOkToExecuteInstruction(entityId: EntityId, instructionDetail: InstructionDetail): Boolean = {
    val expectedEntityTypes =
      instructionDetail.detail.get(ENTITY_TYPES_FILTER)
        .map(_.toString.split(",").toSet).getOrElse(Set.empty)

    val currEntityType =
      if (entityId.equals(ENTITY_ID_GLOBAL)) "global"
      else if (SubnetUtilsExt.isClassfulIpAddress(entityId)) "ip"
      else if (isUserIdOwner(entityId)) "user-owner"
      else if (isUserIdCounterparty(entityId)) "user-counterparty"
      else throw new RuntimeException("entity type doesn't supported")

    // Must only execute the instruction
    // if there is no expected entity type or
    // violated entity type matches with one of the expected entity type
    expectedEntityTypes.isEmpty || expectedEntityTypes.exists(et => currEntityType.startsWith(et))
  }

  def buildTrackingData(violatedRule: ViolatedRule, actionDetail: InstructionDetail): (EntityId, Int) = {
    val periodInSec = actionDetail.detail.getOrElse("period", "-1").toString.toInt
    val entityId: String = violatedRule.entityId
    (entityId, periodInSec)
  }
}


object LogLevelValidator extends InstructionDetailValidator {
  val instructionName: String = LOG_MSG_INSTRUCTION
  val keyName = "level"
  val _required: Boolean = false

  def validate(keyPath: String, value: Any): Unit = {
    value match {
      case "trace"| "debug" | "info" | "warn" | "error" =>
      case _ => throw new InvalidValueException(Option(
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
        case _ => throw new InvalidValueException(Option(
          s"$keyPath contains unsupported value: $value"))
      }
    } catch {
      case _: NumberFormatException =>
        throw new InvalidValueException(Option(
          s"$keyPath contains non numeric value: $value"))
    }
  }
}

class EntityTypesValidator(val instructionName: String, val required: Boolean = true) extends InstructionDetailValidator {
  val keyName = "entity-types"
  val _required: Boolean = required

  def validate(keyPath: String, value: Any): Unit = {
    value match {
      case "global" | "ip" | "user" | "user-owner" | "user-counterparty" =>
      case _ => throw new InvalidValueException(Option(
        s"$keyPath contains unsupported value: $value"))
    }
  }
}

object LogMsgInstruction extends Instruction {

  val logger: Logger = getLoggerByName("LogMsgInstruction")
  override val name: String = LOG_MSG_INSTRUCTION
  override val validators = Set(LogLevelValidator, new EntityTypesValidator(name, required = false))

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
  override val validators = Set(new PeriodValidator(name), new EntityTypesValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (entityId, warnPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceWarningStatusMngr(WarnResourceForCaller(entityId,
      violatedRule.resourceName, warnPeriod=Option(warnPeriodInSec)))
  }

}

class WarnUserInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("WarnUserInstruction")
  override val name: String = WARN_ENTITY_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new EntityTypesValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (entityId, warnPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceWarningStatusMngr(WarnCaller(entityId, warnPeriod=Option(warnPeriodInSec)))
  }
}

class BlockResourceInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("BlockResourceInstruction")
  override val name: String = BLOCK_RESOURCE_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new EntityTypesValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (entityId, blockPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceBlockingStatusMngr(BlockResourceForCaller(entityId,
      violatedRule.resourceName, blockPeriod=Option(blockPeriodInSec)))
  }

}

class BlockUserInstruction(val spar: ActorRef) extends Instruction {
  val logger: Logger = getLoggerByName("BlockUserInstruction")
  override val name: String = BLOCK_ENTITY_INSTRUCTION
  override val validators = Set(new PeriodValidator(name), new EntityTypesValidator(name))

  override def execute(violatedRule: ViolatedRule, actionDetail: InstructionDetail)(implicit sender: ActorRef): Unit = {
    val (entityId, blockPeriodInSec) = buildTrackingData(violatedRule, actionDetail)
    spar ! ForResourceBlockingStatusMngr(BlockCaller(entityId, blockPeriod=Option(blockPeriodInSec)))
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

  private def filterTasksToBeExecuted(actionId: String, violatedRule: ViolatedRule)
                             (implicit sender: ActorRef): Map[Instruction, InstructionDetail] = {
    ResourceUsageRuleHelper.resourceUsageRules.actionRules.get(actionId) match {
      case Some (ar) =>
        ar.instructions.flatMap { case (instructionName, instructionDetail) =>
          instructions.find(_.name == instructionName).map { i: Instruction =>
            if (i.isOkToExecuteInstruction(violatedRule.entityId, instructionDetail)) {
              Map(i -> instructionDetail)
            } else Map.empty[Instruction, InstructionDetail]
          }.getOrElse(Map.empty[Instruction, InstructionDetail])
        }
      case None =>
        Map.empty[Instruction, InstructionDetail]
    }
  }

  def execute(actionId: String, violatedRule: ViolatedRule)(implicit sender: ActorRef): TasksExecuted = {
    val tasksToBeExecuted = filterTasksToBeExecuted(actionId, violatedRule)
    var executedCount = 0
    tasksToBeExecuted.foreach { case (i, instructionDetail) =>
      i.execute(violatedRule, instructionDetail)
      executedCount += 1
    }
    executedCount
  }

  type TasksExecuted = Int
}

class UsageViolationActionExecutorValidator extends UsageViolationActionExecutorBase {
  override lazy val singletonParentProxyActor: Option[ActorRef] = Some(ActorRef.noSender)
  override lazy val instructions: Set[Instruction] = buildInstructions()
}
