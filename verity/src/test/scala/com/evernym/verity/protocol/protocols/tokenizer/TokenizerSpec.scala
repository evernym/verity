package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.protocol.engine.external_api_access
import com.evernym.verity.protocol.engine.external_api_access.SignatureResult
import com.evernym.verity.protocol.protocols.tokenizer.State.{TokenCreated, TokenFailed, TokenReceived}
import com.evernym.verity.protocol.protocols.tokenizer.TokenizerMsgFamily.{AskForToken, Requester, SigningTokenErr, Tokenizer}
import com.evernym.verity.protocol.testkit.{MockableWalletAccess, TestsProtocolsImpl}
import com.evernym.verity.testkit.{BasicFixtureSpec, TestWalletHelper}
import com.evernym.verity.util.Base64Util.getBase64Encoded

import scala.language.{implicitConversions, reflectiveCalls}
import scala.util.{Failure, Try}

class TokenizerSpec
  extends TestsProtocolsImpl(TokenizerDefinition)
    with BasicFixtureSpec
    with TestWalletHelper {

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
        s.requester ~ AskForToken(ID, SPONSOR_ID, ComMethodDetail(1, "12345"))
        s.tokenizer.state shouldBe a[TokenFailed]
        s.requester.state shouldBe a[TokenFailed]
        val tokenizerFailure = s.tokenizer.state.asInstanceOf[TokenFailed]
        tokenizerFailure.err shouldBe SigningTokenErr.err
      }
    }
    "should generate a token" in {s =>
      interaction (s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(external_api_access.SignatureResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(ID, SPONSOR_ID, ComMethodDetail(1, "12345"))
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
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(external_api_access.SignatureResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(ID, SPONSOR_ID, ComMethodDetail(1, "12345"))
        s.tokenizer.role shouldBe Tokenizer
        s.tokenizer.state shouldBe a[TokenCreated]
        val token = s.requester.state.asInstanceOf[TokenReceived]
        token.token.sponseeId shouldBe ID

        val id2 = ID + 2
        s.requester ~ AskForToken(id2, SPONSOR_ID, ComMethodDetail(1, "12345"))
        s.tokenizer.state shouldBe a[TokenCreated]
        val token2 = s.requester.state.asInstanceOf[TokenReceived]
        token2.token.sponseeId shouldBe id2

      }
    }

    //TODO: This should eventually be handled in Jackson parser - work around to get release out
    "should fail if value protocol message contains null" in { s =>
      interaction(s.requester, s.tokenizer) {
        s.tokenizer walletAccess MockableWalletAccess.alwaysSignAs(Try(external_api_access.SignatureResult("SIGN".getBytes, "V1")))
        s.requester ~ AskForToken(null, SPONSOR_ID, ComMethodDetail(1, "12345"))
        assertFailedState(s.tokenizer.state, "missing argName: sponseeId")
      }
    }
    def assertFailedState(state: TokenizerState, err: String): Unit = {
      state.asInstanceOf[TokenFailed].err shouldEqual err
    }
  }

  override val containerNames: Set[ContainerName] = Set(TestingVars.REQUESTER, TestingVars.TOKENIZER)
}

object TestingVars extends CommonSpecUtil {
  val REQUESTER = "requester"
  val TOKENIZER = "tokenizer"
  val ID = "abc"
  val SPONSOR_ID = "efg"
}
