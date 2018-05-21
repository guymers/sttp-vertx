package com.softwaremill.sttp
package vertx

import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.Locale

import scala.collection.JavaConverters._
import scala.language.higherKinds
import scala.util.Try
import scala.util.control.NonFatal

import com.softwaremill.sttp.SttpBackendOptions.ProxyType
import com.softwaremill.sttp.syntax._
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.{ProxyType => VertxProxyType}
import io.vertx.core.streams.Pump

abstract class VertxBackend[R[_], S](
  vertx: Vertx,
  options: HttpClientOptions
)(implicit MAE: MonadAsyncError[R])
  extends SttpBackend[R, S] {

  private val httpClient = {
    // Vert.x doesnt seem to handle a compressed response when one was not asked
    options.setTryUseCompression(true)

    vertx.createHttpClient(options)
  }
  private val fs = vertx.fileSystem()

  override def responseMonad: MonadError[R] = MAE

  override def send[T](r: Request[T, S]): R[Response[T]] = {

    for {
      response <- sendRequest(r)
      body <- if (codeIsSuccess(response.statusCode())) {
        responseToBody(response, r.response).map(Right(_))
      } else {
        responseToString(response, Utf8).map(Left(_))
      }
    } yield {
      val headers = response
        .headers()
        .iterator()
        .asScala
        .map(e => (e.getKey, e.getValue))
        .toList

      Response(
        body,
        response.statusCode(),
        response.statusMessage(),
        headers,
        Nil
      )
    }
  }

  private def createClientRequest[T](r: Request[T, S]): HttpClientRequest = {
    val methodString = r.method.m.toUpperCase(Locale.ROOT)

    val method = (try Option(HttpMethod.valueOf(methodString))
    catch {
      case _: IllegalArgumentException => None
    }).getOrElse(HttpMethod.OTHER)

    val request = httpClient.requestAbs(method, r.uri.toString)

    if (method == HttpMethod.OTHER) {
      request.setRawMethod(methodString)
    }

    r.headers.foreach { case (name, value) =>
      request.putHeader(name, value)
    }

    request.setFollowRedirects(r.options.followRedirects)

    if (r.options.readTimeout.isFinite()) {
      request.setTimeout(r.options.readTimeout.toMillis)
    }

    request
  }

  private def sendRequest(
    r: Request[_, S]
  ): R[HttpClientResponse] = MAE.async[HttpClientResponse] { cb =>
    def exceptionHandler(t: Throwable): Unit = cb(Left(t))

    val clientRequest = createClientRequest(r)
      .exceptionHandler(e => exceptionHandler(e))
      .handler(r => cb(Right(r)))

    try {
      r.body match {
        case NoBody =>
          clientRequest.end()

        case StringBody(str, encoding, _) =>
          clientRequest.end(str, encoding)

        case ByteArrayBody(b, _) =>
          clientRequest.end(Buffer.buffer(b))

        case ByteBufferBody(bb, _) =>
          clientRequest.end(Buffer.buffer(bb.limit()).setBytes(0, bb))

        case InputStreamBody(is, _) =>
          writeInputStreamToRequest(clientRequest, is, exceptionHandler)

        case PathBody(path, _) =>
          writePathToRequest(clientRequest, path, exceptionHandler)

        case StreamBody(s) =>
          if (!clientRequest.headers().contains(ContentLengthHeader)) {
            clientRequest.setChunked(true)
          }
          writeStreamToRequest(clientRequest, s, exceptionHandler)

        case b @ MultipartBody(_) =>
          writeMultipartToRequest(clientRequest, b, exceptionHandler)
      }
    } catch {
      case NonFatal(e) =>
        clientRequest.reset()
        exceptionHandler(e)
    }
  }

  private def writeInputStreamToRequest(
    clientRequest: HttpClientRequest,
    is: InputStream,
    exceptionHandler: Throwable => Unit
  ): Unit = {
    // not supported by default in Vert.x due to the fact that it blocks
    // https://gist.github.com/Stwissel/a7f8ce79785afd49eb2ced69b56335de
    exceptionHandler(new IllegalStateException("Input streams are blocking, use a streaming body or file instead."))
  }

  private def writePathToRequest(
    clientRequest: HttpClientRequest,
    path: Path,
    exceptionHandler: Throwable => Unit
  ): Unit = {
    val file = path.toFile
    clientRequest.putHeader(ContentLengthHeader, file.length().toString)

    val openOpts = new OpenOptions().setRead(true)

    fs.open(
      file.getAbsolutePath,
      openOpts,
      result => {
        Option(result.result()) match {
          case None => exceptionHandler(result.cause())
          case Some(asyncFile) =>
            asyncFile.exceptionHandler(exceptionHandler(_))
            asyncFile.endHandler(_ => clientRequest.end())

            val pump = Pump.pump(asyncFile, clientRequest)
            pump.start
        }
      }
    )
  }

  protected def writeStreamToRequest(
    clientRequest: HttpClientRequest,
    stream: S,
    exceptionHandler: Throwable => Unit
  ): Unit

  private def writeMultipartToRequest(
    clientRequest: HttpClientRequest,
    body: MultipartBody,
    exceptionHandler: Throwable => Unit
  ): Unit = {
    exceptionHandler(new RuntimeException("multipart request support is not yet implemented"))
  }

  private def responseToBody[T](
    response: HttpClientResponse,
    responseAs: ResponseAs[T, S]
  ): R[T] = {

    // pause the stream until the consumer accesses it
    response.pause()

    def inner[TT](bras: BasicResponseAs[TT, S]): R[TT] = bras match {
      case IgnoreResponse =>
        response.request().connection().close()
        MAE.unit(())

      case ResponseAsString(encoding) =>
        responseToString(response, encoding)

      case ResponseAsByteArray =>
        readEagerResponse(response).map(_.getBytes)

      case ResponseAsFile(output, overwrite) =>
        responseToFile(response, output, overwrite)

      case ras @ ResponseAsStream() =>
        handleResponseAsStream(response).map(ras.responseIsStream)
    }

    responseAs match {
      case bras: BasicResponseAs[t, s] => inner(bras)
      case MappedResponseAs(raw, f) => inner(raw).map(f)
    }
  }

  protected def handleResponseAsStream(response: HttpClientResponse): R[S]

  protected def readEagerResponse[T](response: HttpClientResponse): R[Buffer]

  private def responseToString(response: HttpClientResponse, encoding: String) = {
    readEagerResponse(response).flatMap { buffer =>
      val charset = encodingFromResponse(response).getOrElse(encoding)

      MAE.fromTry(Try { buffer.toString(charset) })
    }
  }

  private def encodingFromResponse(response: HttpClientResponse) = for {
    headers <- Option(response.headers())
    contentTypeHeader <- Option(headers.get(ContentTypeHeader))
    encoding <- encodingFromContentType(contentTypeHeader)
  } yield encoding

  private def responseToFile(
    response: HttpClientResponse,
    output: File,
    overwrite: Boolean
  ): R[File] = {
//    if (!overwrite && output.exists()) {
//      MAE.error(new IOException(s"File ${output.getAbsolutePath} exists - overwriting prohibited"))
//    } else MAE.async[File] { cb =>
//      response.exceptionHandler((t: Throwable) => cb(Left(t)))
//      response.endHandler(_ => cb(Right(output)))
//
//      val openOpts = new OpenOptions()
//        .setWrite(true)
//        .setCreate(true)
//
//      fs.open(output.getAbsolutePath, openOpts, result => {
//        val file = result.result()
//        val pump = Pump.pump(response, file)
//        pump.start
//      })
//    }
    MAE.error(new RuntimeException("file response support is not yet implemented"))
  }

  override def close(): Unit = {
    httpClient.close()
  }
}

object VertxBackend {

  def applySttpBackendOptions(
    baseOpts: HttpClientOptions,
    options: SttpBackendOptions
  ): HttpClientOptions = {

    val opts = baseOpts
      .setConnectTimeout(options.connectionTimeout.toMillis.toInt)

    options.proxy.foreach { p =>
      val proxyOpts = new ProxyOptions()
        .setHost(p.host)
        .setPort(p.port)
        .setType(p.proxyType match {
          case ProxyType.Http => VertxProxyType.HTTP
          case ProxyType.Socks => VertxProxyType.SOCKS5
        })

      opts.setProxyOptions(proxyOpts)
    }

    opts
  }
}
