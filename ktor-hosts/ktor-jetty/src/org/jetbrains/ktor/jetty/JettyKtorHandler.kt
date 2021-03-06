package org.jetbrains.ktor.jetty

import kotlinx.coroutines.experimental.*
import org.eclipse.jetty.io.*
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.jetbrains.ktor.cio.*
import org.jetbrains.ktor.cio.ByteBufferPool
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.http.*
import java.nio.*
import javax.servlet.*
import javax.servlet.http.*

internal class JettyKtorHandler(val environment: ApplicationHostEnvironment, server: Server, val pipeline: () -> HostPipeline) : AbstractHandler() {
//    private val dispatcher by lazy { JettyCoroutinesDispatcher(server.threadPool) }
    private val dispatcher by lazy { environment.executor.asCoroutineDispatcher() }
    private val MULTI_PART_CONFIG = MultipartConfigElement(System.getProperty("java.io.tmpdir"))

    override fun handle(target: String, baseRequest: Request, request: HttpServletRequest, response: HttpServletResponse) {
        val call = JettyApplicationCall(environment.application, server, request, response, byteBufferPool, { call, block, next ->
            if (baseRequest.httpChannel.httpTransport.isPushSupported) {
                baseRequest.pushBuilder.apply {
                    val builder = DefaultResponsePushBuilder(call)
                    builder.block()

                    this.method(builder.method.value)
                    this.path(builder.url.encodedPath)
                    this.queryString(builder.url.build().substringAfter('?', ""))

                    push()
                }
            } else {
                next()
            }
        })

        try {
            val contentType = request.contentType
            if (contentType != null && contentType.startsWith("multipart/")) {
                baseRequest.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG)
                // TODO someone reported auto-cleanup issues so we have to check it
            }

            request.startAsync()
            request.asyncContext.timeout = 0 // Overwrite any default non-null timeout to prevent multiple dispatches
            baseRequest.isHandled = true

            launch(dispatcher) {
                try {
                    pipeline().execute(call)
                } finally {
                    request.asyncContext?.complete()
                }
            }
        } catch(ex: Throwable) {
            environment.log.error("Application ${environment.application::class.java} cannot fulfill the request", ex)

            launch(dispatcher) {
                call.respond(HttpStatusCode.InternalServerError)
            }
        }
    }

    private class Ticket(bb: ByteBuffer) : ReleasablePoolTicket(bb)

    private val byteBufferPool = object : ByteBufferPool {
        val jbp = MappedByteBufferPool(16)

        override fun allocate(size: Int) = Ticket(jbp.acquire(size, false).apply { clear() })
        override fun release(buffer: PoolTicket) {
            jbp.release(buffer.buffer)
            (buffer as Ticket).release()
        }
    }
}