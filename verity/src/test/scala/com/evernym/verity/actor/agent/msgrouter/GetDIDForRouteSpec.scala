package com.evernym.verity.actor.agent.msgrouter

import com.evernym.verity.constants.Constants.{VALID_DID_BYTE_LENGTH, VALID_VER_KEY_BYTE_LENGTH}
import com.evernym.verity.Exceptions.InvalidValueException
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter.getDIDForRoute
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.Base58Util
import com.evernym.verity.testkit.BasicSpec


import scala.util.Failure

class GetDIDForRouteSpec extends AgentMsgRouteSpecValues with BasicSpec {

  "AgentMsgRouter" - {
    "when unit testing getDIDForRoute" - {

      "should return DID when a DID is given" in {
        getDIDForRoute(did1) map {
          d: DID =>
            assert(d == did1)
        }

        getDIDForRoute(did2) map {
          d: DID =>
            assert(d == did2)
        }
      }

      "should return DID when a verkey is given" in {
        getDIDForRoute(verkey1) map {
          d: DID =>
            assert(d == did1)
        }

        getDIDForRoute(verkey2) map {
          d: DID =>
            assert(d == did2)
        }
      }

      "should return error when a non base58 string is given" in {
        val r = getDIDForRoute("ab0sosali121")
        r shouldBe Failure(_: InvalidValueException)
      }

      "should return error when a base58 string of incorrect length is given" in {
        val shorterThanDID = "33QEu799H9R9Zs2uB"
        Base58Util.decode(shorterThanDID).get.length should be < VALID_DID_BYTE_LENGTH
        val r1 = getDIDForRoute(shorterThanDID)
        r1 shouldBe Failure(_: InvalidValueException)

        val longerThanVerkey = "5NmY7h7j12UUtAHJnrE6pnxYwU6fWCcmDWxunZg3cbPvox73grhdUf2kJubdzV6TxY"
        Base58Util.decode(longerThanVerkey).get.length should be > VALID_VER_KEY_BYTE_LENGTH
        val r2 = getDIDForRoute(longerThanVerkey)
        r2 shouldBe Failure(_: InvalidValueException)

        val longerThanDIDShorterThanVerkey = "21xgiZ212fnrxGkCbopaxjTc"
        Base58Util.decode(longerThanDIDShorterThanVerkey).get.length should be > VALID_DID_BYTE_LENGTH
        Base58Util.decode(longerThanDIDShorterThanVerkey).get.length should be < VALID_VER_KEY_BYTE_LENGTH
        val r3 = getDIDForRoute(longerThanDIDShorterThanVerkey)
        r3 shouldBe Failure(_: InvalidValueException)
      }
    }
  }
}