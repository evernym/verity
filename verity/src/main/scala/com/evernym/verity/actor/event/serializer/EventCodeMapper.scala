package com.evernym.verity.actor.event.serializer

import scalapb.GeneratedMessageCompanion


trait EventCodeMapper {

  def eventCodeMapping: Map[Int, GeneratedMessageCompanion[_]]

  lazy val eventCodeMapperWithClassName = {
    eventCodeMapping.map { e =>
      val registeredEventClassName = e._2.getClass.getCanonicalName.replace("$", "")
      e._1 -> registeredEventClassName
    }
  }

  def getCodeFromClass(evt: Any): Int = {
    val eventClassName = evt.getClass.getCanonicalName

    //TODO: need to find out if there is any better way of doing this
    val results = eventCodeMapperWithClassName.filter(e => eventClassName == e._2)

    results.size match {
      case 0 => throw new EventCodeMappingNotFoundException("no event code mapping found for event: " + evt.getClass)
      case 1 => results.head._1
      case x => throw new MoreEventCodeMappingFoundException(s"$x event code mappings found for event: " + evt.getClass)
    }
  }

  def getClassFromCode(eventCode: Int, eventBinaryData: Array[Byte]): Any = {
    val gmc = eventCodeMapping.getOrElse(eventCode, throw new RuntimeException("event code mapping for code: " + eventCode))
    gmc.parseFrom(eventBinaryData)
  }
}

class EventCodeMappingNotFoundException(msg: String) extends RuntimeException(msg)

class MoreEventCodeMappingFoundException(msg: String) extends RuntimeException(msg)
