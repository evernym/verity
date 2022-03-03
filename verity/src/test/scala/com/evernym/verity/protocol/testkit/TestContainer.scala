package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine._

import scala.collection.BuildFrom
import scala.collection.generic.CanBuildFrom
import scala.language.postfixOps

class TrappingSeq[T](val wrapped: Seq[T]) extends Seq[T] {
  var trapped: Vector[T] = Vector.empty

  override def length: Int = wrapped.length
  override def apply(idx: Int): T = wrapped.apply(idx)
  override def iterator: Iterator[T] = wrapped.iterator

  override def :+[B >: T, That](elem: B)(implicit bf: CanBuildFrom[Seq[T], B, That]): That = {
    val rtn = wrapped :+ elem
    trapped = trapped :+ wrapped.head
    rtn
  }
}

object TrappingSeq {
  def apply[T](wrapped: Seq[T]): TrappingSeq[T] = new TrappingSeq(wrapped)
}

class InterceptSeq[T](val wrapped: Seq[T], transform: Any => Any) extends Seq[T] {
  override def length: Int = wrapped.length
  override def apply(idx: Int): T = wrapped.apply(idx)
  override def iterator: Iterator[T] = wrapped.iterator

  override def :+[B >: T, That](elem: B)(implicit bf: BuildFrom[Seq[T], B, That]): That = {
    val i = elem match {
      case Envelope1(msg, to, fr, mid, tid) => Envelope1(transform(msg), to, fr, mid, tid)
      case Envelope2(msg, frm)              => Envelope2(transform(msg), frm)
      case e => transform(e)
    }
    wrapped :+ i.asInstanceOf[T]
  }
}
object InterceptSeq {
  def apply[T](wrapped: Seq[T], transform: Any => Any): InterceptSeq[T] = new InterceptSeq(wrapped, transform)
}

//class TestContainer[P,R,M,E,S,I](system: SimpleProtocolSystem,
//                                  participantId: ParticipantId,
//                                  pinstId: PinstId,
//                                  threadId: Option[ThreadId],
//                                  definition: ProtocolDefinition[P,R,M,E,S,I],
//                                  initProvider: InitProvider,
//                                  _eventRecorder: Option[RecordsEvents]=None,
//                                  containerDriver: Option[Driver]=None,
//                                  parentLogContext: JournalContext=JournalContext(),
//                                  walletAccessProvider: Option[()=>WalletAccess] = None,
//                                )
//                                (implicit tag: ClassTag[M])
//  extends InMemoryProtocolContainer[P,R,M,E,S,I] ( ProtocolContainerElements(
//    system,
//    participantId,
//    pinstId,
//    threadId,
//    definition,
//    initProvider,
//    _eventRecorder,
//    containerDriver,
//    parentLogContext,
//    walletAccessProvider)
//  )
//  with Assertions {
//
//  var signalDef: Any => Unit = super.signal
//  override def signal(signal: Any): Unit = signalDef(signal)
//
//  def interceptInbound(transform: Any => Any,op: => Unit): Unit = {
//    val original = inbox.queue
//    try {
//      inbox.queue = InterceptSeq(original, transform)
//
//      op
//    }
//    finally inbox.queue = original
//  }
//
//  def interceptOutbound(transform: Any => Any, op: => Unit): Unit = {
//    val original = outbox.queue
//    try{
//      outbox.queue = InterceptSeq(original, transform)
//
//      op
//    }
//    finally outbox.queue = original
//  }
//
//  def expectSignalType[T](op: => Unit)(implicit t: ClassTag[T]): T = {
//    val original: Any => Unit = signalDef
//    try {
//      val trapped: mutable.ListBuffer[Any] = mutable.ListBuffer.empty
//      val replacement:Any => Unit = { s =>
//        trapped += s
//        original(s)
//      }
//      signalDef = replacement
//
//      op
//
//      val expectedClass = t.runtimeClass.asInstanceOf[Class[T]]
//      trappedClassAssert(expectedClass, trapped).asInstanceOf[T]
//    }
//    finally signalDef = original
//  }
//
//  def expectOutboxType[T](op: => Unit)(implicit t: ClassTag[T]): T = {
//    val queue: Seq[ProtocolOutgoingMsg[Any]] = outbox.queue
//    try{
//      val wrappedQueue = TrappingSeq(queue)
//      outbox.queue = wrappedQueue
//      val trapped = wrappedQueue.trapped
//
//      op
//
//      val expectedClass = t.runtimeClass.asInstanceOf[Class[T]]
//      trappedClassAssert(expectedClass, trapped).msg.asInstanceOf[T]
//    }
//    finally outbox.queue = queue
//  }
//
//  private def trappedClassAssert[T](expectedClass: Class[_], seq: Seq[T]): T = {
//    assert(seq.nonEmpty, "Trapped messages should not be empty")
//    val rtn = seq.filter({
//      o =>
//        o.getClass.isAssignableFrom(expectedClass)
//    })
//    assert(rtn.nonEmpty, s"No trapped messages match expected type - ${expectedClass.getSimpleName}")
//    rtn.head
//  }
//
//  override lazy val wallet: WalletAccess = new WalletAccessController(
//    grantedAccessRights,
//    walletAccessProvider.map(_()).getOrElse(MockableWalletAccess())
//  )
//}
