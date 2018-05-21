package com.softwaremill.sttp.vertx

import io.vertx.core.Vertx
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite

trait VertxTest extends BeforeAndAfterAll { self: Suite =>

  protected var vertx: Vertx = _

  override protected def beforeAll(): Unit = {
    vertx = Vertx.vertx()
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    vertx.close()
  }
}
