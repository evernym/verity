package com.evernym.verity

import com.evernym.verity.testkit.BasicAsyncSpec

import scala.concurrent.Promise

class PromiseSpec extends BasicAsyncSpec {

  "A promise" - {
    "can resolve at some later time" in {

      val p = Promise[Int]()

      val x = p.future

      val y = x.map(_ + 4)

      y.isCompleted shouldBe false
      x.isCompleted shouldBe false

      p.success(5)

      x.isCompleted shouldBe true

      //TODO: need to confirm if the change is correct or not
      y.map { t =>
        assert( t == 9)
      }
    }



  }

}
