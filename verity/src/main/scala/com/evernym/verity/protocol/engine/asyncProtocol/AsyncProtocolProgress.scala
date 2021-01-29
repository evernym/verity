package com.evernym.verity.protocol.engine.asyncProtocol

trait AsyncProtocolProgress {

  var asyncServices: Set[AsyncProtocolService] = Set()

  def addsAsyncProtocolService(s: AsyncProtocolService): Unit = asyncServices += s
  def removesAsyncProtocolService(s: AsyncProtocolService): Unit = asyncServices -= s
  def clearInternalAsyncServices(): Unit = asyncServices = Set()


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
