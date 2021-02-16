package com.evernym.verity

import com.evernym.verity.Exceptions.InvalidComMethodException
import com.evernym.verity.testkit.BasicSpec

class UrlParamSpec
  extends BasicSpec {

  "when asked to parse url" - {
    "should respond with proper parsed url detail" in {
      val caught1 = intercept[InvalidComMethodException] { UrlParam("xyz://127.0.0.1:9000/agent/callback") }
      assert(caught1.getMessage.contains("unknown protocol: xyz"))

      val caught2 = intercept[InvalidComMethodException] { UrlParam("localhost") }
      assert(caught2.getMessage.contains("no protocol: localhost"))

      val caught3 = intercept[InvalidComMethodException] { UrlParam("127.128.129.130") }
      assert(caught3.getMessage.contains("no protocol: 127.128.129.130"))

      UrlParam("127.0.0.1:9000/agent/callback") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"))
      UrlParam("127.0.0.1:9000/agent/callback?k1=v1") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"), Option("k1=v1"))

      UrlParam("http://127.0.0.1:9000/agent/callback?k1=v1") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"), Option("k1=v1"))
      UrlParam("https://127.0.0.1:9000/agent/callback") shouldBe UrlParam("https", "127.0.0.1", 9000, Option("agent/callback"))

      UrlParam("http://localhost:9000/agent/callback") shouldBe UrlParam("http", "localhost", 9000, Option("agent/callback"))
      UrlParam("https://localhost:9001/agent/callback1?k1=v1") shouldBe UrlParam("https", "localhost", 9001, Option("agent/callback1"), Option("k1=v1"))

      UrlParam("https://localhost/agent/callback1?k1=v1") shouldBe UrlParam("https", "localhost", 443, Option("agent/callback1"), Option("k1=v1"))
      UrlParam("http://localhost/agent/callback1") shouldBe UrlParam("http", "localhost", 80, Option("agent/callback1"))

      UrlParam("https://localhost.com/agent/callback1") shouldBe UrlParam("https", "localhost.com", 443, Option("agent/callback1"))
      UrlParam("https://localhost.com/agent/callback1") shouldBe UrlParam("https", "localhost.com", 443, Option("agent/callback1"))

      UrlParam("http://localhost:9000/agent/callback").isHttp shouldBe true
      UrlParam("http://localhost:9000/agent/callback").isHttps shouldBe false
      UrlParam("https://localhost.com/agent/callback1").isHttps shouldBe true
      UrlParam("https://localhost.com/agent/callback1").isHttp shouldBe false

      UrlParam("https://localhost.com/agent/callback1").url shouldBe "https://localhost.com:443/agent/callback1"
      UrlParam("https://localhost.com/agent/callback1").path shouldBe "agent/callback1"
    }
  }
  "when asked to instantiate UrlParam object with different constructors" - {
    "should be able to do it successfully" in {
      UrlParam("localhost:9001/agency/msg") shouldBe UrlParam("localhost:9001/agency/msg")
      UrlParam("http://localhost:9001/agency/msg") shouldBe UrlParam("localhost", 9001, Some("agency/msg"))
      UrlParam("localhost:9001/agency/msg") shouldBe UrlParam("localhost", 9001, Some("agency/msg"))
      UrlParam("http://api.enym.com/agency/msg") shouldBe UrlParam("api.enym.com", 80, Some("agency/msg"))
      UrlParam("https://api.enym.com/agency/msg") shouldBe UrlParam("api.enym.com", 443, Some("agency/msg"))
    }
  }

  "when asked url param methods" - {
    "should respond accordingly" in {
      val up = UrlParam("https://localhost:9001/agent/callback1?k1=v1")
      up.isHttps shouldBe true
      up.isHttp shouldBe false
      up.isLocalhost shouldBe true

      up.url shouldBe "https://localhost:9001/agent/callback1?k1=v1"
      up.path shouldBe "agent/callback1"
    }
  }
}
