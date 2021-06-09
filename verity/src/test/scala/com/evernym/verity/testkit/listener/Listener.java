package com.evernym.verity.testkit.listener;

import org.apache.http.*;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class Listener {
    private Integer port;
    private MsgHandler handler;
    private HttpServer server;

    static final Logger logger = LoggerFactory.getLogger(Listener.class);

    public Listener(Integer port, MsgHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    //TODO: confirm if this is ok?
    public void updateMsgHandler(MsgHandler handler) {
        this.handler = handler;
    }

    public void listen() throws IOException {
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout(15000)
                .setTcpNoDelay(true)
                .build();

        server = ServerBootstrap.bootstrap()
                .setListenerPort(this.port)
                .setServerInfo("Test/1.1")
                .setSocketConfig(socketConfig)
                .setExceptionLogger(new StdErrorExceptionLogger())
                .registerHandler("*", new HttpHandler())
                .create();

        server.start();
    }

    public void stop() {
        server.shutdown(1, TimeUnit.SECONDS);
    }

    static class StdErrorExceptionLogger implements ExceptionLogger {

        @Override
        public void log(final Exception ex) {
            if (ex instanceof SocketTimeoutException) {
                logger.error("Connection timed out -- " + ex.getMessage());
            } else if (ex instanceof ConnectionClosedException) {
                logger.debug("ConnectionClosedException: "+ex.getMessage());
            } else {
                logger.debug("Unexpected : "+ex.getMessage());
                ex.printStackTrace();
            }
        }

    }

    class HttpHandler implements HttpRequestHandler  {

        HttpHandler() {
            super();
        }

        public void handle(
                final HttpRequest request,
                final HttpResponse response,
                final HttpContext context) throws HttpException, IOException {

            String method = request.getRequestLine().getMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST")) {
                throw new MethodNotSupportedException(method + " method not supported");
            }
            String target = request.getRequestLine().getUri();
            Header host = request.getFirstHeader(HttpHeaders.HOST);
            logger.debug("Got request for " + method + " " + host.getValue() + target);

            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                byte[] entityContent = EntityUtils.toByteArray(entity);
                logger.debug("Dispatching enclosed request entity to handler");
                Listener.this.handler.handler(entityContent);
            }

            HttpCoreContext coreContext = HttpCoreContext.adapt(context);
            HttpConnection conn = coreContext.getConnection(HttpConnection.class);
            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new StringEntity("Success", StandardCharsets.UTF_8));
            logger.debug(conn + ": serving data \"Success\"");
        }
    }
}
