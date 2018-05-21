package com.softwaremill.sttp.vertx

import scala.concurrent.duration.TimeUnit
import scala.concurrent.duration._

import io.vertx.core.Context
import io.vertx.core.Vertx
import monix.execution.Cancelable
import monix.execution.ExecutionModel
import monix.execution.Scheduler
import monix.execution.UncaughtExceptionReporter
import monix.execution.schedulers.BatchingScheduler
import monix.execution.schedulers.ReferenceScheduler

/**
  * @param vertx
  * @param executionModel
  * @param uncaughtExceptionReporter used if there is no exceptionHandler defined for the vertx context
  */
class VertxScheduler(
  vertx: Vertx,
  uncaughtExceptionReporter: UncaughtExceptionReporter,
  override val executionModel: ExecutionModel
) extends Scheduler with BatchingScheduler with ReferenceScheduler {

  val ctx: Context = vertx.getOrCreateContext()

  override protected def executeAsync(r: Runnable): Unit = {
    ctx.runOnContext(_ => r.run())
  }

  override def reportFailure(t: Throwable): Unit = {
    Option(ctx.exceptionHandler()) match {
      case None => uncaughtExceptionReporter.reportFailure(t)
      case Some(exceptionHandler) => exceptionHandler.handle(t)
    }
  }

  override def scheduleOnce(initialDelay: Long, unit: TimeUnit, r: Runnable): Cancelable = {
    val delay = (initialDelay, unit).toMillis
    if (delay <= 0) {
      execute(r)
      Cancelable.empty
    } else {
      val id = vertx.setTimer(delay, _ => execute(r))
      Cancelable(() => vertx.cancelTimer(id))
    }
  }

  // look at possibly using vertx.setPeriodic() to implement scheduleAtFixedRate
}

object VertxScheduler {

  def apply(vertx: Vertx, uncaughtExceptionReporter: UncaughtExceptionReporter): VertxScheduler = {
    new VertxScheduler(vertx, uncaughtExceptionReporter, ExecutionModel.Default)
  }
}
