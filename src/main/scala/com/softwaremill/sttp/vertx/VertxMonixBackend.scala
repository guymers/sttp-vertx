package com.softwaremill.sttp
package vertx

import java.nio.ByteBuffer

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.softwaremill.sttp.impl.monix.TaskMonadAsyncError
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.streams.WriteStream
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.execution.UncaughtExceptionReporter
import monix.reactive.Observable
import monix.reactive.observers.SafeSubscriber
import monix.reactive.observers.Subscriber

class VertxMonixBackend private(
  vertx: Vertx,
  options: HttpClientOptions,
  uncaughtExceptionReporter: UncaughtExceptionReporter
) extends VertxBackend[Task, Observable[ByteBuffer]](vertx, options)(TaskMonadAsyncError) {

  private val scheduler = VertxScheduler(vertx, uncaughtExceptionReporter)

  override protected def readEagerResponse[T](
    response: HttpClientResponse
  ): Task[Buffer] = {
    Task.create[Buffer] { case (_, cb) =>

      val contentLength = Option(response.getHeader(ContentLengthHeader))
        .flatMap(l => Try(l.toInt).toOption)
        .getOrElse(0)

      val buffer = Buffer.buffer(contentLength)

      response.exceptionHandler(t => cb(Left(t)))
      response.handler(b => buffer.appendBuffer(b))
      response.endHandler(_ => cb(Right(buffer)))

      response.resume()

      Cancelable(() => close(response))
    }
  }

  // TODO test back pressure
  override protected def handleResponseAsStream(
    response: HttpClientResponse
  ): Task[Observable[ByteBuffer]] = Task.delay {

    Observable.unsafeCreate[Buffer] { sub =>

      response.handler { b =>
        scheduler.ctx.runOnContext { _ =>
          val result = sub.onNext(b)

          if (!result.isSynchronous) {
            response.pause()
          }

          result.syncOnComplete {
            case Success(Ack.Continue) => response.resume()
            case Success(Ack.Stop) => close(response)
            case Failure(e) => try close(response) finally sub.onError(e)
          }(scheduler)
        }
      }
      response.exceptionHandler { e =>
        close(response)
        scheduler.ctx.runOnContext(_ => sub.onError(e))
      }
      response.endHandler { _ =>
        close(response)
        scheduler.ctx.runOnContext(_ => sub.onComplete())
      }

      response.resume()

      Cancelable.apply(() => close(response))

    }.map(b => ByteBuffer.wrap(b.getBytes))
  }

  override protected def writeStreamToRequest(
    clientRequest: HttpClientRequest,
    stream: Observable[ByteBuffer],
    exceptionHandler: Throwable => Unit
  ): Unit = {
    val sub = createSubscriber(clientRequest, exceptionHandler)
    stream.map(byteBuffer => Buffer.buffer().setBytes(0, byteBuffer)).subscribe(sub)
  }

  // TODO test back pressure
  private def createSubscriber(ws: WriteStream[Buffer], exceptionHandler: Throwable => Unit) = {
    val _scheduler = scheduler

    SafeSubscriber(new Subscriber[Buffer] {

      // the contract requires that onNext() must not occur concurrently
      // https://monix.io/docs/2x/reactive/observers.html#contract
      private var waitingForWriteQueue: Option[Promise[Ack]] = None

      ws.drainHandler { _ =>
        waitingForWriteQueue.foreach(_.completeWith(Ack.Continue))
        waitingForWriteQueue = None
      }
      ws.exceptionHandler { t =>
        waitingForWriteQueue match {
          case None => exceptionHandler(t)
          case Some(p) => p.failure(t)
        }
      }

      override implicit def scheduler: Scheduler = _scheduler

      // same logic as io.vertx.core.streams.impl.PumpImpl
      override def onNext(elem: Buffer): Future[Ack] = {
        _scheduler.ctx.runOnContext(_ => ws.write(elem))
        if (ws.writeQueueFull()) {
          val p = Promise[Ack]()
          waitingForWriteQueue = Some(p)
          p.future
        } else {
          Ack.Continue
        }
      }
      override def onError(t: Throwable): Unit = exceptionHandler(t)
      override def onComplete(): Unit = _scheduler.ctx.runOnContext(_ => ws.end())
    })
  }

  private def close(response: HttpClientResponse): Unit = {
    response.request().connection().close()
  }

}

object VertxMonixBackend {

  private def apply(
    vertx: Vertx,
    options: HttpClientOptions,
    uncaughtExceptionReporter: UncaughtExceptionReporter
  ): SttpBackend[Task, Observable[ByteBuffer]] = {
    new FollowRedirectsBackend(new VertxMonixBackend(vertx, options, uncaughtExceptionReporter))
  }

  def apply(
    vertx: Vertx,
    options: SttpBackendOptions,
    uncaughtExceptionReporter: UncaughtExceptionReporter
  ): SttpBackend[Task, Observable[ByteBuffer]] = {
    val opts = new HttpClientOptions()
    VertxMonixBackend(vertx, VertxBackend.applySttpBackendOptions(opts, options), uncaughtExceptionReporter)
  }

  def usingConfig(
    vertx: Vertx,
    options: HttpClientOptions,
    uncaughtExceptionReporter: UncaughtExceptionReporter
  ): SttpBackend[Task, Observable[ByteBuffer]] = {
    VertxMonixBackend(vertx, options, uncaughtExceptionReporter)
  }

}
