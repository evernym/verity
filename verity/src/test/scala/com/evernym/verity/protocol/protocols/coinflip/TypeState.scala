package com.evernym.verity.protocol.protocols.coinflip

import java.lang.reflect.Modifier

import com.evernym.verity.protocol.engine.Roster

//import com.evernym.verity.agentmsg.msgfamily.protocol.ConnectionData
//import com.evernym.verity.protocol.Role

import scala.reflect.ClassTag

class IllegalStateTransition extends Exception

trait TypeState[Data <: Object, StateColl, R] {
  def transitions: Seq[Class[_]]
  def data: Data

  lazy private val legalTransitions = {
    transitions.map { t =>
      val name = t.getCanonicalName
      if (name.endsWith("$")) name.substring(0, name.length-1)
      else name
    }
  }

  private def createNewStateFromClass(clazz: Class[_], data: Data): Any = {
    if (clazz.getName == "scala.runtime.Nothing$")
      throw new IllegalArgumentException("Transition State type must be specified")

    if (Modifier.isAbstract(clazz.getModifiers))
      throw new IllegalArgumentException(s"Transition State [${clazz.getName}] must not be abstract")

    val constructor = clazz.getConstructors()(0)
    val args: Seq[Object] = Seq(data)
    constructor.newInstance(args:_*)
  }

  private def createNewStateFromType[T <: StateColl: ClassTag](data: Data): StateColl = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val o = createNewStateFromClass(clazz, data)
    o.asInstanceOf[T]
  }

  private def isLegalTransition(newState: StateColl): StateColl = {
    val newClass = newState.getClass.getCanonicalName

    if ((newClass == this.getClass.getCanonicalName) ||
      (legalTransitions contains newClass)
    )
      newState
    else {
      val msg = "Illegal transition from "
      throw new IllegalStateTransition()
    }
  }

  def transitionTo[T <: StateColl: ClassTag](data: Data): StateColl = {
    val newState = createNewStateFromType[T](data)
    isLegalTransition(newState)
  }

  def transitionTo[T <: StateColl: ClassTag]: StateColl = {
    transitionTo[T](this.data)
  }

  def updateData(data: Data): StateColl = {
    val o = createNewStateFromClass(this.getClass, data)
    o.asInstanceOf[StateColl]
  }
}

