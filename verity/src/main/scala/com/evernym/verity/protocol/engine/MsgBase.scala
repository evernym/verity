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

  def checkRequired(fieldName: String, fieldValue: Any, allowEmpty: Boolean = false): Unit = {
    // check if null
    if (Option(fieldValue).isEmpty) throwMissingReqFieldException(fieldName)
    fieldValue match {
      case mb: MsgBase => mb.validate()
      case s: String => if (!allowEmpty && s.trim.isEmpty) throwMissingReqFieldException(fieldName)
      case l: List[Any] =>
        if (!allowEmpty && l.isEmpty) throwMissingReqFieldException(fieldName)
        l.foreach(checkRequired(s"$fieldName item", _))
      case v: Vector[Any] =>
        if (!allowEmpty && v.isEmpty) throwMissingReqFieldException(fieldName)
        v.foreach(checkRequired(s"$fieldName item", _))
      case s: Seq[Any] =>
        if (!allowEmpty && s.isEmpty) throwMissingReqFieldException(fieldName)
        s.foreach(checkRequired(s"$fieldName item", _))
      case m: Map[_, _] =>
        if (!allowEmpty && m.isEmpty) throwMissingReqFieldException(fieldName)
        for ((key, value) <- m) {
          checkRequired(s"$fieldName key", key)
          checkRequired(s"$fieldName value", value, allowEmpty = true)
        }
      case _ =>
    }
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
