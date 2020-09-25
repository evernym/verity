package com.evernym.verity.actor.testkit.checks

import java.io.OutputStream

/**
  * Useful for inspecting an output stream, like Console.out.
  *
  * @param chained existing output stream that you want to tee off of
  * @param tee new output stream
  */
class TeeOutputStream[A <: OutputStream, B <: OutputStream](val chained: A, val tee: B) extends OutputStream {

  /**
    * Implementation for parent's abstract write
    * method. This writes the passed-in character
    * to both the chained stream and the "tee"
    * stream.
    */
  def write(c: Int): Unit = {
    chained.write(c)
    tee.write(c)
    tee.flush()
  }

  /**
    * Closes tee. Assumes chained will have its own independent lifecycle.
    */
  override def close(): Unit = {
    flush()
    tee.close()
  }

  /**
    * Flushes chained stream; the tee stream is
    * flushed each time a character is written to
    * it.
    */
  override def flush(): Unit = {
    chained.flush()
  }
}
