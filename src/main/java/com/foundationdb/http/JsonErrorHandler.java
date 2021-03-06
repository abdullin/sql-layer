/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foundationdb.http;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;

import com.foundationdb.rest.RestResponseBuilder;
import com.foundationdb.server.error.ErrorCode;
import org.eclipse.jetty.server.handler.ErrorHandler;

public class JsonErrorHandler extends ErrorHandler {
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
        baseRequest.setHandled(true);
        String method = request.getMethod();
        if(!method.equals(HttpMethods.GET) && !method.equals(HttpMethods.POST) && !method.equals(HttpMethods.HEAD)) {
            return;
        }

        final String message;
        final ErrorCode error;
        if(response.getStatus() == HttpServletResponse.SC_NOT_FOUND) {
            message = "Path not supported; try /v1";
            error = ErrorCode.MALFORMED_REQUEST;
        } else {
            message = HttpStatus.getMessage(response.getStatus());
            error = ErrorCode.INTERNAL_ERROR;
        }

        response.setContentType(MediaType.APPLICATION_JSON);
        response.setHeader(HttpHeaders.CACHE_CONTROL, getCacheControl());

        StringBuilder builder = new StringBuilder();
        RestResponseBuilder.formatJsonError(builder, error.getFormattedValue(), message);
        builder.append('\n');

        response.setContentLength(builder.length());
        OutputStream out = response.getOutputStream();
        out.write(builder.toString().getBytes());
        out.close();
    }
}
