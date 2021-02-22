package com.evernym.verity.protocol.engine.asyncProtocol

trait AsyncProtocolProgress {

  var asyncServices: Map[AsyncProtocolService, Int] = Map()

  def addsAsyncProtocolService(s: AsyncProtocolService): Unit = {
    val cur = asyncServices.getOrElse(s, 0)
    s match {
        // SegmentStateStore does not support stacking properly, this is workaround.
      case SegmentStateStoreProgress if cur == 1 =>
      case _ => asyncServices += (s -> (cur + 1))
    }
  }

  def removesAsyncProtocolService(s: AsyncProtocolService): Unit = {
    val cur = asyncServices.getOrElse(s, 0)
    if (cur > 1)
      asyncServices += (s -> (cur - 1))
    else
      asyncServices -= s
  }

  def clearInternalAsyncServices(): Unit = asyncServices = Map()


  /**
   * Things like the url shortener and the wallet and ledger services are internal to a protocol and need to be complete
   *  before segmented state storage and event persistent which are post protocol.
   */
  def asyncProtocolServicesComplete(): Boolean = asyncServices.isEmpty
}

trait AsyncProtocolService
case object UrlShorteningProgress extends AsyncProtocolService
case object LedgerProgress extends AsyncProtocolService
case object WalletProgress extends AsyncProtocolService
case object SegmentStateStoreProgress extends AsyncProtocolService
