package com.evernym.verity.testkit

import com.evernym.verity.actor.testkit.checks.ChecksLogs
import org.scalatest.OptionValues
import org.scalatest.freespec.{AnyFreeSpecLike, AsyncFreeSpecLike, FixtureAnyFreeSpecLike}

trait BasicSpecBase
  extends Matchers
    with OptionValues {
    /** the following trait helps in the troubleshooting
     * process when isolating a failing test; can be
     * commented out otherwise.
     **/
    //with CancelGloballyAfterFailure

    def checkArrayEquality(expected: Option[Array[Byte]], actual: Option[Array[Byte]]) = {
      expected.map(e => new String(e)) shouldBe actual.map(a => new String(a))
    }
}


trait BasicSpec
  extends AnyFreeSpecLike
    with BasicSpecBase
    //with ChecksLogs
    //with ChecksConsole  //TODO this should be enabled


trait BasicAsyncSpec
  extends AsyncFreeSpecLike
    with BasicSpecBase
    //with ChecksLogsAsync      //TODO this should be enabled
    //with ChecksConsoleAsync   //TODO this should be enabled


trait BasicFixtureSpec
  extends FixtureAnyFreeSpecLike
    with BasicSpecBase


trait BasicSpecWithIndyCleanup
  extends BasicSpec
    with CleansUpIndyClientFirst