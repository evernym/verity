package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.wallet.SignedMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.asyncapi.wallet.SignedMsgResult
import com.evernym.verity.protocol.protocols.tokenizer.State.{TokenCreated, TokenFailed, TokenReceived}
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.{AskForToken, Requester, SigningTokenErr, Tokenizer}
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, HasTestWalletAPI}
import com.evernym.verity.util.Base64Util.getBase64Encoded
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.language.{implicitConversions, reflectiveCalls}
import scala.util.{Failure, Try}


class TokenizerSpec
  extends TestsProtocolsImpl(TokenizerDefinition)
    with BasicFixtureSpec
    with HasTestWalletAPI {

  import TestingVars._

  private implicit def EnhancedScenario(s: Scenario) = new {
    val requester: TestEnvir = s(REQUESTER)
    val tokenizer: TestEnvir = s(TOKENIZER)
  }

  "Tokenizer Protocol Definition" - {
    "should have two roles" in { _ =>
      TokenizerDefinition.roles.size shouldBe 2
      TokenizerDefinition.roles shouldBe Set(Requester, Tokenizer)
    }
  }

  "A GetToken msg" - {
    "should fail signing token" in {s =>
      interaction (s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Failure(SigningTokenErr))
        s.requester ~ AskForToken(ID, SPONSOR_ID)
        s.tokenizer.state shouldBe a[TokenFailed]
        s.requester.state shouldBe a[TokenFailed]
        val tokenizerFailure = s.tokenizer.state.asInstanceOf[TokenFailed]
        tokenizerFailure.err shouldBe SigningTokenErr.err
      }
    }
    "should generate a token" in {s =>
      interaction (s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(SignedMsgResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(ID, SPONSOR_ID)
        s.requester.role shouldBe Requester
        s.tokenizer.role shouldBe Tokenizer
        s.tokenizer.state shouldBe a[TokenCreated]
        s.requester.state shouldBe a[TokenReceived]
        val token = s.requester.state.asInstanceOf[TokenReceived]
        token.token.sig shouldBe getBase64Encoded("SIGN".getBytes)
      }
    }

    "should generate a token again" in {s =>
      interaction (s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(SignedMsgResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(ID, SPONSOR_ID)
        s.tokenizer.role shouldBe Tokenizer
        s.tokenizer.state shouldBe a[TokenCreated]
        val token = s.requester.state.asInstanceOf[TokenReceived]
        token.token.sponseeId shouldBe ID

        val id2 = ID + 2
        s.requester ~ AskForToken(id2, SPONSOR_ID, Option(ComMethodDetail(1, "12345", hasAuthEnabled = false)))
        s.tokenizer.state shouldBe a[TokenCreated]
        val token2 = s.requester.state.asInstanceOf[TokenReceived]
        token2.token.sponseeId shouldBe id2

      }
    }

    "should generate a token without push id" in {s =>
      interaction (s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(SignedMsgResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(ID, SPONSOR_ID)
        s.tokenizer.role shouldBe Tokenizer
        s.tokenizer.state shouldBe a[TokenCreated]
        val token = s.requester.state.asInstanceOf[TokenReceived]
        token.token.sponseeId shouldBe ID
      }
    }

    //TODO: This should eventually be handled in Jackson parser - work around to get release out
    "should fail if value protocol message contains null" in { s =>
      interaction(s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(SignedMsgResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(null, SPONSOR_ID)
        assertFailedState(s.tokenizer.state, "missing argName: sponseeId")
      }
    }
    def assertFailedState(state: TokenizerState, err: String): Unit = {
      state.asInstanceOf[TokenFailed].err shouldEqual err
    }
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.REQUESTER, TestingVars.TOKENIZER)

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def appConfig: AppConfig = TestExecutionContextProvider.testAppConfig
}

object TestingVars {
  val REQUESTER = "requester"
  val TOKENIZER = "tokenizer"
  val ID = "abc"
  val SPONSOR_ID = "efg"
}
