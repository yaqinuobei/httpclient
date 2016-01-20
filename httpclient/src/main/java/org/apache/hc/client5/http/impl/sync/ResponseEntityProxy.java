/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.impl.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;

import org.apache.hc.client5.http.io.EofSensorInputStream;
import org.apache.hc.client5.http.io.EofSensorWatcher;
import org.apache.hc.core5.annotation.NotThreadSafe;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.entity.HttpEntityWrapper;

/**
 * A wrapper class for {@link HttpEntity} enclosed in a response message.
 *
 * @since 4.3
 */
@NotThreadSafe
class ResponseEntityProxy extends HttpEntityWrapper implements EofSensorWatcher {

    private final ConnectionHolder connHolder;

    public static void enchance(final HttpResponse response, final ConnectionHolder connHolder) {
        final HttpEntity entity = response.getEntity();
        if (entity != null && entity.isStreaming() && connHolder != null) {
            response.setEntity(new ResponseEntityProxy(entity, connHolder));
        }
    }

    ResponseEntityProxy(final HttpEntity entity, final ConnectionHolder connHolder) {
        super(entity);
        this.connHolder = connHolder;
    }

    private void cleanup() throws IOException {
        if (this.connHolder != null) {
            this.connHolder.close();
        }
    }

    private void abortConnection() {
        if (this.connHolder != null) {
            this.connHolder.abortConnection();
        }
    }

    public void releaseConnection() {
        if (this.connHolder != null) {
            this.connHolder.releaseConnection();
        }
    }

    @Override
    public boolean isRepeatable() {
        return false;
    }

    @Override
    public InputStream getContent() throws IOException {
        return new EofSensorInputStream(super.getContent(), this);
    }

    @Override
    public void writeTo(final OutputStream outstream) throws IOException {
        try {
            super.writeTo(outstream);
            releaseConnection();
        } catch (IOException | RuntimeException ex) {
            abortConnection();
            throw ex;
        } finally {
            cleanup();
        }
    }

    @Override
    public boolean eofDetected(final InputStream wrapped) throws IOException {
        try {
            // there may be some cleanup required, such as
            // reading trailers after the response body:
            wrapped.close();
            releaseConnection();
        } catch (IOException | RuntimeException ex) {
            abortConnection();
            throw ex;
        } finally {
            cleanup();
        }
        return false;
    }

    @Override
    public boolean streamClosed(final InputStream wrapped) throws IOException {
        try {
            final boolean open = connHolder != null && !connHolder.isReleased();
            // this assumes that closing the stream will
            // consume the remainder of the response body:
            try {
                wrapped.close();
                releaseConnection();
            } catch (final SocketException ex) {
                if (open) {
                    throw ex;
                }
            }
        } catch (IOException | RuntimeException ex) {
            abortConnection();
            throw ex;
        } finally {
            cleanup();
        }
        return false;
    }

    @Override
    public boolean streamAbort(final InputStream wrapped) throws IOException {
        cleanup();
        return false;
    }

}
