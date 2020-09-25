package com.evernym.verity.actor.testkit.checks.specs

import java.io.{ByteArrayOutputStream, FileOutputStream, OutputStreamWriter, PrintWriter}

import com.evernym.verity.actor.testkit.checks.TeeOutputStream
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.testkit.BasicSpec


import scala.io.Source

class TeeOutputStreamSpec extends BasicSpec with TempDir {

  "TeeOutputStream" - {
    "can split a stream into two" in {

      val tmpFile = tempFile("test", ".out")

      val fos = new FileOutputStream(tmpFile.getAbsolutePath)

      val inmem = new ByteArrayOutputStream()

      val tee = new TeeOutputStream(fos, inmem)

      val lines = Vector(
        "Testing line 1",
        "Testing line 2",
        "Testing line 3: qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM1234567890",
        "Testing line 4: wertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM123456789",
        "Testing line 5: ertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM12345678",
        "Testing line 6: rtyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM1234567",
        "Testing line 7: tyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM123456"
      )

      //write test lines to tee output stream
      val pw = new PrintWriter(new OutputStreamWriter(tee))
      lines foreach pw.println
      pw.flush()
      pw.close()

      //read test lines from file
      val source = Source.fromFile(tmpFile.getAbsolutePath)
      val fileLines = try {
        source.getLines().toList
      } finally {
        source.close()
      }

      //read test lines from byte array
      val arrayLines = Source.fromString(inmem.toString).getLines().toList

      //ensure inputs and two outputs are all equal
      fileLines shouldBe lines
      arrayLines shouldBe lines

    }
  }
}
