package com.twitter.querulous.query

import com.twitter.finagle.tracing.{Tracer, Annotation, Trace}
import com.twitter.finagle.util.CloseNotifier
import com.twitter.util.{Promise, Return}
import java.sql.Connection
import java.net.{UnknownHostException, InetSocketAddress, InetAddress}
import java.nio.ByteBuffer

/**
 * Adds trace annotations to capture data about the query.
 * This data is then processed and sent off with a Finagle compatible tracer.
 * @param query The query to execute
 * @param connection Connection to execute query on
 * @param queryClass Class of Query
 * @param serviceName The service name we want to associate with the traces generated by this query
 * @param tracer The tracer we want to send traces to
 * @param annotateQuery Do we want to annotate the query or not? Annotate attaches information about this
 * trace to the queries we send. For example we attach the trace id so that we can look up
 * information about where this query comes from.w
 */
class TracingQuery(query: Query,
                   connection: Connection,
                   queryClass: QueryClass,
                   serviceName: String,
                   tracer: Tracer,
                   annotateQuery: Boolean) extends QueryProxy(query: Query) {

  override protected def delegate[A](f: => A) = {
    Trace.unwind {
      Trace.pushTracer(tracer)
      // generate the id for this span, decide to sample or not
      val nextId = Trace.nextId
      val sampled = Trace.id.sampled orElse tracer.sampleTrace(nextId)
      Trace.pushId(nextId.copy(sampled = sampled))

      val address = getLocalAddress(connection)
      address foreach { Trace.recordClientAddr(_) }

      // do we want to annotate this query at all?
      if (annotateQuery) {
        // set the ip and service name to help debugging
        address foreach { addr => query.addAnnotation("client_host", addr.getAddress.getHostAddress) }
        query.addAnnotation("service_name", serviceName)

        // only set trace id if we have decided to sample this trace
        if (sampled.getOrElse(false)) {
          query.addAnnotation("trace_id", nextId.traceId.toString())
        }
      }

      // we want to know which query caused these timings
      getRootQuery match {
        case q: SqlQuery =>
          Trace.record(Annotation.BinaryAnnotation("querulous.query", ByteBuffer.wrap(q.query.getBytes())))
        case _ => ()
      }
      Trace.recordRpcname(serviceName, queryClass.name)

      // send request and time it
      Trace.record(Annotation.ClientSend())
      val rv = f
      Trace.record(Annotation.ClientRecv())
      rv
    }
  }

  def getLocalAddress(connection: Connection): Option[InetSocketAddress] = {
    try {
      // don't know the port
      Some(new InetSocketAddress(
        InetAddress.getByName(connection.getClientInfo("ClientHostname")), 0))
    } catch {
      case e: UnknownHostException => None
    }
  }
}

class TracingQueryFactory(queryFactory: QueryFactory,
                          serviceName: String,
                          tracerFactory: Tracer.Factory,
                          annotateQuery: Boolean) extends QueryFactory {

  private[this] val closing = new Promise[Unit]
  private[this] val closeNotifier = CloseNotifier.makeLifo(closing)
  private[this] val tracer = tracerFactory(closeNotifier)

  def apply(connection: Connection, queryClass: QueryClass, query: String, params: Any*) = {
    new TracingQuery(queryFactory(connection, queryClass, query, params: _*),
      connection, queryClass, serviceName, tracer, annotateQuery)
  }

  override def shutdown() = { closing.updateIfEmpty(Return(())) }
}
