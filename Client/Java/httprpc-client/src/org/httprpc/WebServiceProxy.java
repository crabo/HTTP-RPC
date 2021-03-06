/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.httprpc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Invocation proxy for HTTP-RPC web services.
 */
public class WebServiceProxy {
    // Invocation callback
    private class InvocationCallback<V> implements Callable<V> {
        private String method;
        private String path;
        private Map<String, ?> arguments;
        private ResultHandler<V> resultHandler;

        private static final String POST_METHOD = "POST";

        private static final String ACCEPT_LANGUAGE_KEY = "Accept-Language";

        private static final String CONTENT_TYPE_KEY = "Content-Type";
        private static final String MULTIPART_FORM_DATA_MIME_TYPE = "multipart/form-data";
        private static final String BOUNDARY_PARAMETER_FORMAT = "; boundary=%s";

        private static final String OCTET_STREAM_MIME_TYPE = "application/octet-stream";

        private static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition: form-data";
        private static final String NAME_PARAMETER_FORMAT = "; name=\"%s\"";
        private static final String FILENAME_PARAMETER_FORMAT = "; filename=\"%s\"";

        private static final String CRLF = "\r\n";

        private static final int EOF = -1;

        public InvocationCallback(String method, String path, Map<String, ?> arguments, ResultHandler<V> resultHandler) {
            this.method = method;
            this.path = path;
            this.arguments = arguments;
            this.resultHandler = resultHandler;
        }

        @Override
        public V call() throws Exception {
            final V result;
            try {
                result = invoke();
            } catch (final Exception exception) {
                if (resultHandler != null) {
                    dispatchResult(new Runnable() {
                        @Override
                        public void run() {
                            resultHandler.execute(null, exception);
                        }
                    });
                }

                throw exception;
            }

            if (resultHandler != null) {
                dispatchResult(new Runnable() {
                    @Override
                    public void run() {
                        resultHandler.execute(result, null);
                    }
                });
            }

            return result;
        }

        @SuppressWarnings("unchecked")
        private V invoke() throws Exception {
            URL url = new URL(serverURL, path);

            // Construct query
            if (!method.equalsIgnoreCase(POST_METHOD)) {
                StringBuilder queryBuilder = new StringBuilder();

                for (Map.Entry<String, ?> argument : arguments.entrySet()) {
                    String name = argument.getKey();

                    if (name == null) {
                        continue;
                    }

                    List<?> values = getParameterValues(argument.getValue());

                    for (int i = 0, n = values.size(); i < n; i++) {
                        Object value = values.get(i);

                        if (value == null) {
                            continue;
                        }

                        if (queryBuilder.length() > 0) {
                            queryBuilder.append("&");
                        }

                        queryBuilder.append(URLEncoder.encode(name, UTF_8_ENCODING));
                        queryBuilder.append("=");
                        queryBuilder.append(URLEncoder.encode(getParameterValue(value), UTF_8_ENCODING));
                    }
                }

                if (queryBuilder.length() > 0) {
                    url = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + queryBuilder);
                }
            }

            // Open URL connection
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();

            connection.setRequestMethod(method);

            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);

            // Set language
            Locale locale = Locale.getDefault();
            String acceptLanguage = locale.getLanguage().toLowerCase() + "-" + locale.getCountry().toLowerCase();

            connection.setRequestProperty(ACCEPT_LANGUAGE_KEY, acceptLanguage);

            // Authenticate request
            if (authentication != null) {
                authentication.authenticateRequest(connection);
            }

            // Write request body
            if (method.equalsIgnoreCase(POST_METHOD)) {
                connection.setDoOutput(true);

                String boundary = UUID.randomUUID().toString();
                String requestContentType = MULTIPART_FORM_DATA_MIME_TYPE + String.format(BOUNDARY_PARAMETER_FORMAT, boundary);

                connection.setRequestProperty(CONTENT_TYPE_KEY, requestContentType);

                String boundaryData = String.format("--%s%s", boundary, CRLF);

                // Write request body
                try (OutputStream outputStream = new MonitoredOutputStream(connection.getOutputStream())) {
                    OutputStreamWriter writer = new OutputStreamWriter(outputStream, Charset.forName(UTF_8_ENCODING));

                    for (Map.Entry<String, ?> argument : arguments.entrySet()) {
                        String name = argument.getKey();

                        if (name == null) {
                            continue;
                        }

                        List<?> values = getParameterValues(argument.getValue());

                        for (Object value : values) {
                            if (value == null) {
                                continue;
                            }

                            writer.append(boundaryData);

                            writer.append(CONTENT_DISPOSITION_HEADER);
                            writer.append(String.format(NAME_PARAMETER_FORMAT, name));

                            if (value instanceof URL) {
                                String path = ((URL)value).getPath();
                                String filename = path.substring(path.lastIndexOf('/') + 1);

                                writer.append(String.format(FILENAME_PARAMETER_FORMAT, filename));
                                writer.append(CRLF);

                                String attachmentContentType = URLConnection.guessContentTypeFromName(filename);

                                if (attachmentContentType == null) {
                                    attachmentContentType = OCTET_STREAM_MIME_TYPE;
                                }

                                writer.append(String.format("%s: %s%s", CONTENT_TYPE_KEY, attachmentContentType, CRLF));
                                writer.append(CRLF);

                                writer.flush();

                                try (InputStream inputStream = ((URL)value).openStream()) {
                                    int b;
                                    while ((b = inputStream.read()) != EOF) {
                                        outputStream.write(b);
                                    }
                                }
                            } else {
                                writer.append(CRLF);

                                writer.append(CRLF);
                                writer.append(getParameterValue(value));
                            }

                            writer.append(CRLF);
                        }
                    }

                    writer.append(String.format("--%s--%s", boundary, CRLF));

                    writer.flush();
                }
            }

            // Read response
            int responseCode = connection.getResponseCode();

            Object result;
            if (responseCode / 100 == 2) {
                try (InputStream inputStream = new MonitoredInputStream(connection.getInputStream())) {
                    result = decodeResponse(inputStream, connection.getContentType());
                }
            } else {
                throw new IOException(String.format("%d %s", responseCode, connection.getResponseMessage()));
            }

            return (V)result;
        }
    }

    // Monitored input stream
    private static class MonitoredInputStream extends BufferedInputStream {
        public MonitoredInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public int read() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }

            return super.read();
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }

            return super.read(b, off, len);
        }
    }

    // Monitored output stream
    private static class MonitoredOutputStream extends BufferedOutputStream {
        public MonitoredOutputStream(OutputStream outputStream) {
            super(outputStream);
        }

        @Override
        public void write(int b) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }

            super.write(b);
        }

        @Override
        public void write(byte b[], int off, int len) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }

            super.write(b, off, len);
        }
    }

    private URL serverURL;
    private ExecutorService executorService;
    private int connectTimeout;
    private int readTimeout;

    private Authentication authentication = null;

    private static final String UTF_8_ENCODING = "UTF-8";

    private static final String JSON_MIME_TYPE = "application/json";

    /**
     * Creates a new HTTP-RPC service proxy.
     *
     * @param serverURL
     * The server URL.
     *
     * @param executorService
     * The executor service that will be used to execute requests.
     */
    public WebServiceProxy(URL serverURL, ExecutorService executorService) {
        this(serverURL, executorService, 0, 0);
    }

    /**
     * Creates a new HTTP-RPC service proxy.
     *
     * @param serverURL
     * The server URL.
     *
     * @param executorService
     * The executor service that will be used to execute requests.
     *
     * @param connectTimeout
     * The connect timeout.
     *
     * @param readTimeout
     * The read timeout.
     */
    public WebServiceProxy(URL serverURL, ExecutorService executorService, int connectTimeout, int readTimeout) {
        if (serverURL == null) {
            throw new IllegalArgumentException();
        }

        if (executorService == null) {
            throw new IllegalArgumentException();
        }

        this.serverURL = serverURL;
        this.executorService = executorService;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Returns the server URL.
     *
     * @return
     * The server URL.
     */
    public URL getServerURL() {
        return serverURL;
    }

    /**
     * Returns the executor service that is used to execute requests.
     *
     * @return
     * The executor service that is used to execute requests.
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Returns the service proxy's authentication provider.
     *
     * @return
     * The service proxy's authentication provider.
     */
    public Authentication getAuthentication() {
        return authentication;
    }

    /**
     * Sets the service proxy's authentication provider.
     *
     * @param authentication
     * The service proxy's authentication provider, or <tt>null</tt> for no
     * authentication provider.
     */
    public void setAuthentication(Authentication authentication) {
        this.authentication = authentication;
    }

    /**
     * Executes a service operation.
     *
     * @param <V> The type of the value returned by the operation.
     *
     * @param method
     * The HTTP verb associated with the request.
     *
     * @param path
     * The path associated with the request.
     *
     * @param resultHandler
     * A callback that will be invoked upon completion of the request, or
     * <tt>null</tt> for no result handler.
     *
     * @return
     * A future representing the invocation request.
     */
    @SuppressWarnings("unchecked")
    public <V> Future<V> invoke(String method, String path, ResultHandler<V> resultHandler) {
        return invoke(method, path, Collections.EMPTY_MAP, resultHandler);
    }

    /**
     * Executes a service operation.
     *
     * @param <V> The type of the value returned by the operation.
     *
     * @param method
     * The HTTP verb associated with the request.
     *
     * @param path
     * The path associated with the request.
     *
     * @param arguments
     * The request arguments.
     *
     * @param resultHandler
     * A callback that will be invoked upon completion of the request, or
     * <tt>null</tt> for no result handler.
     *
     * @return
     * A future representing the invocation request.
     */
    public <V> Future<V> invoke(String method, String path, Map<String, ?> arguments, ResultHandler<V> resultHandler) {
        if (method == null) {
            throw new IllegalArgumentException();
        }

        if (path == null) {
            throw new IllegalArgumentException();
        }

        if (arguments == null) {
            throw new IllegalArgumentException();
        }

        return executorService.submit(new InvocationCallback<>(method, path, arguments, resultHandler));
    }

    /**
     * Decodes a response value.
     *
     * @param inputStream
     * The input stream to read from.
     *
     * @param contentType
     * The MIME type of the content, or <tt>null</tt> if the content type is
     * unknown.
     *
     * @return
     * The decoded value, or <tt>null</tt> if the value could not be decoded.
     *
     * @throws IOException
     * If an exception occurs.
     */
    protected Object decodeResponse(InputStream inputStream, String contentType) throws IOException {
        Object value = null;

        if (contentType != null && contentType.startsWith(JSON_MIME_TYPE)) {
            Decoder decoder = new JSONDecoder();

            value = decoder.readValue(inputStream);
        }

        return value;
    }

    /**
     * Dispatches a result value.
     *
     * @param command
     * A callback representing the result handler and the associated result or
     * exception.
     */
    protected void dispatchResult(Runnable command) {
        command.run();
    }

    /**
     * Creates a list from a variable length array of elements.
     *
     * @param elements
     * The elements from which the list will be created.
     *
     * @return
     * An immutable list containing the given elements.
     */
    @SafeVarargs
    public static List<?> listOf(Object...elements) {
        return Collections.unmodifiableList(Arrays.asList(elements));
    }

    /**
     * Creates a map from a variable length array of map entries.
     *
     * @param <K> The type of the key.
     *
     * @param entries
     * The entries from which the map will be created.
     *
     * @return
     * An immutable map containing the given entries.
     */
    @SafeVarargs
    public static <K> Map<K, ?> mapOf(Map.Entry<K, ?>... entries) {
        HashMap<K, Object> map = new HashMap<>();

        for (Map.Entry<K, ?> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }

        return Collections.unmodifiableMap(map);
    }

    /**
     * Creates a map entry.
     *
     * @param <K> The type of the key.
     *
     * @param key
     * The entry's key.
     *
     * @param value
     * The entry's value.
     *
     * @return
     * An immutable map entry containing the key/value pair.
     */
    public static <K> Map.Entry<K, ?> entry(K key, Object value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private static List<?> getParameterValues(Object argument) throws UnsupportedEncodingException {
        List<?> values;
        if (argument instanceof List<?>) {
            values = (List<?>)argument;
        } else {
            values = Collections.singletonList(argument);
        }

        return values;
    }

    private static String getParameterValue(Object element) {
        if (!(element instanceof String || element instanceof Number || element instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid parameter element.");
        }

        return element.toString();
    }
}
