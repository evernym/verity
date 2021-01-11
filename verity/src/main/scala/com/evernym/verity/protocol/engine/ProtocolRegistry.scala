package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.CtlEnvelope
import com.evernym.verity.protocol.engine.PinstIdResolution.DEPRECATED_V0_1
import com.evernym.verity.protocol.engine.ProtocolRegistry.Entry
import com.evernym.verity.protocol.engine.segmentedstate.SegmentStoreStrategy
import com.evernym.verity.protocol.engine.util.?=>

import scala.language.implicitConversions

/**
  * Holds a number of protocol definitions. Immutable, so it expects all of
  * the protocol definitions at creation. If adding another protocol to an
  * existing registry is desired, then create a new one with `copy`.
  *
  * If more than one protocol supports the same message (which should not
  * be the case), then the protocol definition selected will be the first
  * in the order they were listed when constructing the registry.
  *
  * @param entries a vararg list of protocol definitions and driver generators to include in the registry.
  * @tparam A type of the input parameter to the driverGen function in Entry
  */
case class ProtocolRegistry[-A](entries: Entry[A]*) {

  require(
    entries.count(_.pinstIdResol == DEPRECATED_V0_1) <= 6,
    "new protocols should use 'V0_2' instead of 'DEPRECATED_V0_1' as a pinst id resolver"
  )

  // "Driver" here is the same thing as the concept of a "controller" from
  // https://github.com/hyperledger/aries-rfcs/tree/master/concepts/0003-protocols#controllers?
  // TODO: update to use community terminology.
  def generateDriver(definition: ProtocolDefinition[_,_,_,_,_,_], parameter: A): Option[Driver] = {
    find_!(definition.msgFamily.protoRef).driverGen map { _(parameter) }
  }

  def getNativeClassType(amt: MsgType): Class[_] = {
    find_!(amt.protoRef).protoDef.msgFamily.lookupClassOrElse(amt.msgName, {
      throw new UnsupportedMessageType(amt, registeredProtocols.keys)
    })
  }

  def find(protoRef: ProtoRef): Option[Entry[A]] = registeredProtocols.get(protoRef)

  def find_!(protoRef: ProtoRef): Entry[A] = find(protoRef) getOrElse {
    throw new UnsupportedMessageFamily(protoRef, registeredProtocols.keys)
  }

  private val registeredProtocols: Map[ProtoRef, Entry[A]] =
    entries.map(e => e.protoDef.msgFamily.protoRef -> e).toMap

  def supportedMsg_DEPRECATED: Any ?=> Entry[A] = entries.map(e => e.protoDef.supportedMsgs.andThen(_ => e)).reduce(_ orElse _)
  lazy val inputs: Map[MsgType, Entry[A]] = entries.flatMap { e =>
    e.protoDef.msgFamily.msgTypes.map(mt => mt.normalizedMsgType -> e)
  }.toMap

  private def msgToEntry: TypedMsgLike => Option[Entry[A]] = { tm =>
    if (supportedMsg_DEPRECATED.isDefinedAt(tm.msg)) {
      supportedMsg_DEPRECATED.lift(tm.msg)
    } else {
      inputs.get(tm.msgType.normalizedMsgType)
    }
  }

  def entryForMsg_![B](tmsg: TypedMsgLike): Entry[A] = {
    entryForMsg[B](tmsg) getOrElse {
      throw new UnsupportedMessageType(tmsg.msg, registeredProtocols.keys)
    }
  }

  def entryForMsg[B](tmsg: TypedMsgLike): Option[Entry[A]] = {
    msgToEntry(tmsg)
  }

  def protoDefForMsg[B](tmsg: TypedMsgLike): Option[ProtoDef] = {
    entryForMsg(tmsg).map(_.protoDef)
  }

  def protoDefForMsg_![B](tmsg: TypedMsgLike): ProtoDef = {
    protoDefForMsg(tmsg).getOrElse(
      throw new UnsupportedMessageType(tmsg.msg, registeredProtocols.keys)
    )
  }

  def newWith[B <: A](entries: Entry[B]*): ProtocolRegistry[B] = {
    val es = entries ++ this.entries// ++ entries
    ProtocolRegistry(es: _*)
  }

  private def untypedMsgToEntry: Any => Option[Entry[A]] = { m =>
    if (supportedMsg_DEPRECATED.isDefinedAt(m)) {
      supportedMsg_DEPRECATED.lift(m)
    } else {
      entries.find { e =>
        try {
          e.protoDef.msgFamily.lookupInputMsgName(m.getClass).nonEmpty
        } catch {
          case _: NoSuchElementException => false
        }
      }
    }
  }

  // we can't make a stronger assertion about type because erasure
  private def entryForUntypedMsg(msg: Any): Option[Entry[A]] = {
    val extractedMsg = msg match {
      case env: Envelope[_] => env.msg
      case m: CtlEnvelope[_] => m
      case m => m
    }
    untypedMsgToEntry(extractedMsg)
  }

  def `entryForUntypedMsg_!`(msg: Any): Entry[A] = {
    entryForUntypedMsg(msg).getOrElse(
      throw new UnsupportedMessageType(msg, registeredProtocols.keys)
    )
  }
}

object ProtocolRegistry {

  type DriverGen[-A] = Option[A => Driver]

  /**
    *
    * @param protoDef an instance of a ProtocolDefinition
    * @param driverGen a function that generates a driver from a parameter of type A
    * @tparam A type of the input parameter to the driverGen function
    */
  case class Entry[-A](protoDef: ProtoDef,
                       pinstIdResol: PinstIdResolver,
                       driverGen: DriverGen[A]=None,
                       segmentStoreStrategy: Option[SegmentStoreStrategy]=None)

  implicit def Func2OptFunc[A](func: Function[A,Driver]): Option[Function[A,Driver]] = Option(func)
  implicit def DefResol2Entry[A](t: (ProtoDef, PinstIdResolver)): Entry[A] = Entry(t._1, t._2, None, None)
  implicit def DefResolDriver2Entry[A](t: (ProtoDef, PinstIdResolver, Function[A,Driver])): Entry[A] = {
    Entry(t._1, t._2, Option(t._3), None)
  }
  implicit def DefResolStoreStrategy2Entry[A](t: (ProtoDef, PinstIdResolver, SegmentStoreStrategy)): Entry[A] = {
    Entry(t._1, t._2, None, Option(t._3))
  }
  implicit def FullTuple2Entry[A](t: (ProtoDef, PinstIdResolver, Function[A,Driver], SegmentStoreStrategy)): Entry[A] = {
    Entry(t._1, t._2, Option(t._3), Option(t._4))
  }

}

class UnsupportedMessageType( msg: Any,
                              registered: Iterable[ProtoRef])
  extends ProtocolEngineException(s"no support for msg $msg in registered protocols: ${registered.mkString(", ")}")

class UnsupportedMessageFamily( protoRef: ProtoRef,
                                registered: Iterable[ProtoRef])
  extends ProtocolEngineException(s"no support for msg family $protoRef in registered protocols: ${registered.mkString(", ")}")
