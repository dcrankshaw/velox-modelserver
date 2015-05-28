package edu.berkeley.veloxms

import org.scalatest.{FlatSpec, Matchers}
import org.scalamock.scalatest.MockFactory
import edu.berkeley.veloxms._
import scala.concurrent._
import ExecutionContext.Implicits.global
import dispatch.StatusCode

class ModelSpec extends FlatSpec with Matchers with MockFactory  {
