package com.evernym.verity.protocol.engine.box

import com.evernym.verity.protocol.engine.journal.JournalContext
import com.evernym.verity.protocol.engine.{Driver, SignalEnvelope}
import com.evernym.verity.util.MsgUtil

class SignalOutbox( driver: Option[Driver],
                    inbox: BoxLike[Any,Any],
                    parentLogContext: JournalContext = JournalContext()
                  ) extends BoxLike[SignalEnvelope[_],Unit] {

  val name = "signal-outbox"
  val itemType = "signal message"

  override protected val journalContext: JournalContext = parentLogContext + name

  def processOneItem(sig: SignalEnvelope[_]): Unit = {
    driver foreach { d =>
      try {
        d.signal(sig) map { MsgUtil.encloseCtl(_, sig.threadId) } foreach(inbox.add(_))
      } catch {
        case e: Exception => logger.error("Driver failed to handle signal message", e)
      }
    }
  }

}
