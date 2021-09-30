package com.evernym.verity.vdr

import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.service.{LedgerRegistered, TAAConfig, VDRTools}

import scala.concurrent.{ExecutionContext, Future}

//in-memory version of VDRTools to be used in tests (unit/integration)
class TestVDRTools(implicit ec: ExecutionContext)
  extends VDRTools {

  //TODO: as we add/integrate actual VDR apis and their tests,
  // this class should evolve to reflect the same for its test implementation

  override def registerIndyLedger(namespaces: List[Namespace],
                                  genesisTxnFilePath: String,
                                  taaConfig: Option[TAAConfig]): Future[LedgerRegistered] = {
    ledgerRegistry = ledgerRegistry.withNewLedger(TestIndyLedger(namespaces, genesisTxnFilePath, taaConfig))
    Future.successful(LedgerRegistered())
  }

  private def extractNamespace(fqDidStr: DidStr): Namespace = {
    try {
      fqDidStr.split(":")(1) //TODO: replace with correct/appropriate code
    } catch {
      case _: RuntimeException =>
        throw new RuntimeException("invalid fq did: " + fqDidStr)
    }
  }

  var ledgerRegistry: TestLedgerRegistry = TestLedgerRegistry(List.empty)
}

case class TestLedgerRegistry(ledgers: List[TestLedgerBase]) {
  def withNewLedger(vdr: TestLedgerBase): TestLedgerRegistry = {
    copy(ledgers :+ vdr)
  }
  def vdrByNamespace(namespace: Namespace): Option[TestLedgerBase] =
    ledgers.find(_.namespaces.contains(namespace))
}

//base interface for any VDR (for testing purposes only)
trait TestLedgerBase {
  def namespaces: List[Namespace]
}

case class TestIndyLedger(namespaces: List[Namespace],
                          genesisTxnFilePath: String,
                          taaConfig: Option[TAAConfig])
  extends TestLedgerBase {
}
