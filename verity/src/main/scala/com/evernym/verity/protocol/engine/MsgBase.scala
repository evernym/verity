package com.evernym.verity.protocol.engine

class EmptyValueForOptionalFieldProtocolEngineException(statusMsg: Option[String] = None)
  extends ProtocolEngineException(statusMsg.get)

class MissingReqFieldProtocolEngineException(statusMsg: Option[String] = None)
  extends ProtocolEngineException(statusMsg.get)

trait MsgBase {

  def validate(): Unit = {}

  def throwMissingReqFieldException(fieldName: String): Unit = {
    throw new MissingReqFieldProtocolEngineException(Option(s"required attribute not found (missing/empty/null): '$fieldName'"))
  }

  def throwOptionalFieldValueAsEmptyException(fieldName: String): Unit = {
    throw new EmptyValueForOptionalFieldProtocolEngineException(Option(s"empty value given for optional field: '$fieldName'"))
  }

  def checkRequired(fieldName: String, fieldValue: Any): Unit = {
    if (Option(fieldValue).isEmpty) throwMissingReqFieldException(fieldName)
    fieldValue match {
      case mb: MsgBase => mb.validate()
      case _ =>
    }
  }

  def checkRequired(fieldName: String, fieldValue: List[Any]): Unit = {
    if (Option(fieldValue).isEmpty || fieldValue.isEmpty)
      throwMissingReqFieldException(fieldName)
    fieldValue.foreach {
      case mb: MsgBase => mb.validate()
      case _ =>
    }
  }

  def checkRequired(fieldName: String, fieldValue: String): Unit = {
    val fv = Option(fieldValue)
    if (fv.isEmpty || fv.exists(_.trim.isEmpty)) throwMissingReqFieldException(fieldName)
  }

  def checkOptionalNotEmpty(fieldName: String, fieldValue: Option[Any]): Unit = {
    fieldValue match {
      case Some(s: String) => if (s.trim.isEmpty) throwOptionalFieldValueAsEmptyException(fieldName)
      case Some(mb: MsgBase) => mb.validate()
      case Some(_: Any) => //
      case _ => //
    }
  }
}
