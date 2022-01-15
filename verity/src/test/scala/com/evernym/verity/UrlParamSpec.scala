package com.evernym.verity

import com.evernym.verity.util2.Exceptions.InvalidComMethodException
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.UrlParam

class UrlParamSpec
  extends BasicSpec {

  "UrlParam" - {

    "when asked to parse invalid url" - {
      "should respond with proper error" in {
        val ex1 = intercept[InvalidComMethodException] {
          UrlParam("xyz://127.0.0.1:9000/agent/callback")
        }
        assert(ex1.getMessage.contains("unknown protocol: xyz"))

        val ex2 = intercept[InvalidComMethodException] {
          UrlParam("localhost")
        }
        assert(ex2.getMessage.contains("no protocol: localhost"))

        val ex3 = intercept[InvalidComMethodException] {
          UrlParam("127.128.129.130")
        }
        assert(ex3.getMessage.contains("no protocol: 127.128.129.130"))

        val ex4 = intercept[InvalidComMethodException] {
          UrlParam("ws:/abc")
        }
        assert(ex4.getMessage.contains("unknown protocol: ws"))

        val ex5 = intercept[InvalidComMethodException] {
          UrlParam("abc.xyz.com/agent/callback?k1=v1")
        }
        assert(ex5.getMessage.contains("no protocol: abc.xyz.com/agent/callback?k1=v1"))
      }
    }

    "when asked to parse url without any explicit protocol (with port specified)" - {
      "should respond with proper parsed url with based on given port with default to http" in {
        UrlParam("localhost:9001/agency/msg") shouldBe UrlParam("http", "localhost", 9001, Some("agency/msg"))
        UrlParam("localhost:9000/agent/callback?k1=v1") shouldBe UrlParam("http", "localhost", 9000, Option("agent/callback"), Option("k1=v1"))

        UrlParam("127.0.0.1:9000/agent/callback") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"))
        UrlParam("127.0.0.1:9000/agent/callback?k1=v1") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"), Option("k1=v1"))

        UrlParam("localhost:443/agent") shouldBe UrlParam("https", "localhost", 443, Option("agent"))
        UrlParam("localhost:443/agent/callback") shouldBe UrlParam("https", "localhost", 443, Option("agent/callback"))
        UrlParam("localhost:443/agent/callback?k1=v1") shouldBe UrlParam("https", "localhost", 443, Option("agent/callback"), Option("k1=v1"))

        UrlParam("127.0.0.1:443/agent") shouldBe UrlParam("https", "127.0.0.1", 443, Option("agent"))
        UrlParam("127.0.0.1:443/agent/callback") shouldBe UrlParam("https", "127.0.0.1", 443, Option("agent/callback"))
        UrlParam("127.0.0.1:443/agent/callback?k1=v1") shouldBe UrlParam("https", "127.0.0.1", 443, Option("agent/callback"), Option("k1=v1"))

        UrlParam("api.enym.com:80/agency/msg") shouldBe UrlParam("http", "api.enym.com", 80, Some("agency/msg"))
        UrlParam("api.enym.com:443/agency/msg") shouldBe UrlParam("https", "api.enym.com", 443, Some("agency/msg"))
      }
    }

    "when asked to parse url with http protocol" - {
      "should respond with proper parsed url" in {
        //with ports specified
        UrlParam("http://127.0.0.1:9000/agent/callback?k1=v1") shouldBe UrlParam("http", "127.0.0.1", 9000, Option("agent/callback"), Option("k1=v1"))
        UrlParam("http://localhost:9000/agent/callback") shouldBe UrlParam("http", "localhost", 9000, Option("agent/callback"))
        UrlParam("http://localhost:9001/agency/msg") shouldBe UrlParam("http", "localhost", 9001, Some("agency/msg"))
        UrlParam("http://api.enym.com:80/agency/msg") shouldBe UrlParam("http", "api.enym.com", 80, Some("agency/msg"))

        //no explicit port specified
        UrlParam("http://localhost/agent/callback1") shouldBe UrlParam("http", "localhost", 80, Option("agent/callback1"))
        UrlParam("http://127.0.0.1/agent/callback?k1=v1") shouldBe UrlParam("http", "127.0.0.1", 80, Option("agent/callback"), Option("k1=v1"))
        UrlParam("http://api.enym.com/agency/msg") shouldBe UrlParam("http", "api.enym.com", 80, Some("agency/msg"))
      }
    }

    "when asked to parse url with https protocol" - {
      "should respond with proper parsed url detail" in {
        //with ports specified
        UrlParam("https://127.0.0.1:9000/agent/callback") shouldBe UrlParam("https", "127.0.0.1", 9000, Option("agent/callback"))
        UrlParam("https://localhost:9001/agent/callback1?k1=v1") shouldBe UrlParam("https", "localhost", 9001, Option("agent/callback1"), Option("k1=v1"))
        UrlParam("https://localhost.com:9001/agent/callback1") shouldBe UrlParam("https", "localhost.com", 9001, Option("agent/callback1"))
        UrlParam("https://api.enym.com:443/agency/msg") shouldBe UrlParam("https", "api.enym.com", 443, Some("agency/msg"))

        //no explicit port
        UrlParam("https://localhost/agent/callback1?k1=v1") shouldBe UrlParam("https", "localhost", 443, Option("agent/callback1"), Option("k1=v1"))
        UrlParam("https://localhost.com/agent/callback1") shouldBe UrlParam("https", "localhost.com", 443, Option("agent/callback1"))
        UrlParam("https://localhost/agent/callback1") shouldBe UrlParam("https", "localhost", 443, Option("agent/callback1"))
        UrlParam("https://api.enym.com/agency/msg") shouldBe UrlParam("https", "api.enym.com", 443, Some("agency/msg"))
      }
    }

    "when called helper methods on a given url" - {
      "should respond as expected" in {
        UrlParam("http://localhost:9000/agent/callback").isHttp shouldBe true
        UrlParam("http://localhost:9000/agent/callback").isHttps shouldBe false
        UrlParam("https://localhost.com/agent/callback1").isHttps shouldBe true
        UrlParam("https://localhost.com/agent/callback1").isHttp shouldBe false
        UrlParam("https://localhost.com/agent/callback1").url shouldBe "https://localhost.com/agent/callback1"
        UrlParam("https://localhost.com/agent/callback1").path shouldBe "agent/callback1"
      }
    }

    "when asked url param methods" - {
      "should respond accordingly" in {
        val up1 = UrlParam("https://localhost:9001/agent/callback1?k1=v1")
        up1.isHttps shouldBe true
        up1.isHttp shouldBe false
        up1.isLocalhost shouldBe true
        up1.url shouldBe "https://localhost:9001/agent/callback1?k1=v1"
        up1.path shouldBe "agent/callback1"

        val up2 = UrlParam("http://127.0.0.1:9001/agent/callback1?k1=v1")
        up2.isHttps shouldBe false
        up2.isHttp shouldBe true
        up2.isLocalhost shouldBe false
        up2.url shouldBe "http://127.0.0.1:9001/agent/callback1?k1=v1"
        up2.path shouldBe "agent/callback1"
      }
    }
  }
}
