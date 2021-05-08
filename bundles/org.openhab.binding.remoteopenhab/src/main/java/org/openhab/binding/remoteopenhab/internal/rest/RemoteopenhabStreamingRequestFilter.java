/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.remoteopenhab.internal.rest;

import java.io.IOException;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Inserts Authorization and Cache-Control headers for requests on the streaming REST API.
 *
 * @author Laurent Garnier - Initial contribution
 */
@NonNullByDefault
public class RemoteopenhabStreamingRequestFilter implements ClientRequestFilter {

    private final String credentialToken;

    public RemoteopenhabStreamingRequestFilter(String credentialToken) {
        this.credentialToken = credentialToken;
    }

    @Override
    public void filter(@Nullable ClientRequestContext requestContext) throws IOException {
        if (requestContext != null) {
            MultivaluedMap<String, Object> headers = requestContext.getHeaders();
            if (!credentialToken.isEmpty()) {
                headers.putSingle(HttpHeaders.AUTHORIZATION, "Basic " + credentialToken);
            }
            headers.putSingle(HttpHeaders.CACHE_CONTROL, "no-cache");
        }
    }
}
