package scala.scalanative.junit

// Ported from Scala.js

import org.junit._

import scala.scalanative.junit.utils.JUnitTest

class IgnoreTest {
  @Ignore @Test def onlyTest(): Unit = ()
}

class IgnoreTestAssertions extends JUnitTest
