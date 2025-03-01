/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.http.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.impl.NoStackTraceTimeoutException;
import io.vertx.core.internal.PromiseInternal;
import io.vertx.core.internal.pool.ConnectResult;
import io.vertx.core.internal.pool.ConnectionPool;
import io.vertx.core.internal.pool.PoolConnection;
import io.vertx.core.internal.pool.PoolConnector;
import io.vertx.core.internal.pool.Lease;
import io.vertx.core.internal.pool.PoolWaiter;
import io.vertx.core.spi.metrics.ClientMetrics;
import io.vertx.core.spi.metrics.PoolMetrics;

import java.util.List;
import java.util.function.BiFunction;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class SharedClientHttpStreamEndpoint extends ClientHttpEndpointBase<Lease<HttpClientConnectionInternal>> implements PoolConnector<HttpClientConnectionInternal> {

  /**
   * LIFO pool selector.
   */
  private static final BiFunction<PoolWaiter<HttpClientConnectionInternal>, List<PoolConnection<HttpClientConnectionInternal>>, PoolConnection<HttpClientConnectionInternal>> LIFO_SELECTOR = (waiter, connections) -> {
    int size = connections.size();
    PoolConnection<HttpClientConnectionInternal> selected = null;
    long last = 0L;
    for (int i = 0; i < size; i++) {
      PoolConnection<HttpClientConnectionInternal> pooled = connections.get(i);
      if (pooled.available() > 0) {
        HttpClientConnectionInternal conn = pooled.get();
        if (selected == null) {
          selected = pooled;
        } else {
          if (conn.lastResponseReceivedTimestamp() > last) {
            selected = pooled;
          }
        }
      }
    }
    return selected;
  };

  private final HttpClientImpl client;
  private final ClientMetrics clientMetrics;
  private final HttpChannelConnector connector;
  private final ConnectionPool<HttpClientConnectionInternal> pool;

  public SharedClientHttpStreamEndpoint(HttpClientImpl client,
                                        ClientMetrics clientMetrics,
                                        PoolMetrics poolMetrics,
                                        int queueMaxSize,
                                        int http1MaxSize,
                                        int http2MaxSize,
                                        HttpChannelConnector connector,
                                        Runnable dispose) {
    super(poolMetrics, dispose);

    ConnectionPool<HttpClientConnectionInternal> pool = ConnectionPool.pool(this, new int[]{http1MaxSize, http2MaxSize}, queueMaxSize)
      .connectionSelector(LIFO_SELECTOR).contextProvider(client.contextProvider());

    this.client = client;
    this.clientMetrics = clientMetrics;
    this.connector = connector;
    this.pool = pool;
  }

  @Override
  public Future<ConnectResult<HttpClientConnectionInternal>> connect(ContextInternal context, Listener listener) {
    return connector
      .httpConnect(context)
      .map(connection -> {
        incRefCount();
        connection.evictionHandler(v -> {
          decRefCount();
          listener.onRemove();
        });
        connection.concurrencyChangeHandler(listener::onConcurrencyChange);
        long capacity = connection.concurrency();
        Handler<HttpConnection> connectionHandler = client.connectionHandler();
        if (connectionHandler != null) {
          context.emit(connection, connectionHandler);
        }
        int idx;
        if (connection instanceof Http1xClientConnection) {
          idx = 0;
        } else {
          idx = 1;
        }
        return new ConnectResult<>(connection, capacity, idx);
    });
  }

  @Override
  public boolean isValid(HttpClientConnectionInternal connection) {
    return connection.isValid();
  }

  protected void checkExpired() {
    pool.evict(conn -> !conn.isValid(), ar -> {
      if (ar.succeeded()) {
        List<HttpClientConnectionInternal> lst = ar.result();
        lst.forEach(HttpConnection::close);
      }
    });
  }

  private class Request implements PoolWaiter.Listener<HttpClientConnectionInternal>, Handler<AsyncResult<Lease<HttpClientConnectionInternal>>> {

    private final ContextInternal context;
    private final HttpVersion protocol;
    private final long timeout;
    private final Promise<Lease<HttpClientConnectionInternal>> promise;
    private long timerID;

    Request(ContextInternal context, HttpVersion protocol, long timeout, Promise<Lease<HttpClientConnectionInternal>> promise) {
      this.context = context;
      this.protocol = protocol;
      this.timeout = timeout;
      this.promise = promise;
      this.timerID = -1L;
    }

    @Override
    public void onEnqueue(PoolWaiter<HttpClientConnectionInternal> waiter) {
      onConnect(waiter);
    }

    @Override
    public void onConnect(PoolWaiter<HttpClientConnectionInternal> waiter) {
      if (timeout > 0L && timerID == -1L) {
        timerID = context.setTimer(timeout, id -> {
          pool.cancel(waiter, ar -> {
            if (ar.succeeded() && ar.result()) {
              promise.fail(new NoStackTraceTimeoutException("The timeout of " + timeout + " ms has been exceeded when getting a connection to " + connector.server()));
            }
          });
        });
      }
    }

    @Override
    public void handle(AsyncResult<Lease<HttpClientConnectionInternal>> ar) {
      if (timerID >= 0) {
        context.owner().cancelTimer(timerID);
      }
      promise.handle(ar);
    }

    void acquire() {
      pool.acquire(context, this, protocol == HttpVersion.HTTP_2 ? 1 : 0, this);
    }
  }

  @Override
  protected Future<Lease<HttpClientConnectionInternal>> requestConnection2(ContextInternal ctx, long timeout) {
    PromiseInternal<Lease<HttpClientConnectionInternal>> promise = ctx.promise();
    Request request = new Request(ctx, client.options().getProtocolVersion(), timeout, promise);
    request.acquire();
    return promise.future();
  }

  @Override
  protected void handleClose() {
    pool.close();
  }

  @Override
  protected void dispose() {
    if (clientMetrics != null) {
      clientMetrics.close();
    }
    super.dispose();
  }
}
