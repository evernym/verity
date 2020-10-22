package com.evernym.verity.actor.persistence.object_code_mapper

import scalapb.GeneratedMessageCompanion


trait ObjectCodeMapperBase {

  def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]]

  lazy val objectCodeMapperWithClassName: Map[Int, String] = {
    objectCodeMapping.map { e =>
      val objectClassName = e._2.getClass.getCanonicalName.replace("$", "")
      e._1 -> objectClassName
    }
  }

  def codeFromObject(obj: Any): Int = {
    val objectClassName = obj.getClass.getCanonicalName

    //TODO: need to find out if there is any better way of doing this
    val results = objectCodeMapperWithClassName.filter(e => objectClassName == e._2)

    results.size match {
      case 1 => results.head._1
      case 0 => throw new ObjectCodeMappingNotFoundException("no object code mapping found for object: " + obj.getClass)
      case x => throw new MoreObjectCodeMappingFoundException(s"$x object code mappings found for object: " + obj.getClass)
    }
  }

  def objectTypeFromCode(objectCode: Int): Any = {
    objectCodeMapping.getOrElse(objectCode, throw new RuntimeException("object code mapping not found for code: " + objectCode))
  }

  def objectFromCode(objectCode: Int, objectBinaryData: Array[Byte]): Any = {
    objectTypeFromCode(objectCode).asInstanceOf[GeneratedMessageCompanion[_]].parseFrom(objectBinaryData)
  }
}

class ObjectCodeMappingNotFoundException(msg: String) extends RuntimeException(msg)
class MoreObjectCodeMappingFoundException(msg: String) extends RuntimeException(msg)
