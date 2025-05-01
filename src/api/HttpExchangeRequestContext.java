package api;

import com.sun.net.httpserver.Headers;
import org.apache.commons.fileupload.RequestContext;

import java.io.IOException;
import java.io.InputStream;

public class HttpExchangeRequestContext implements RequestContext {

    private final Headers headers;
    private final InputStream inputStream;

    public HttpExchangeRequestContext(Headers headers, InputStream inputStream) {
        this.headers = headers;
        this.inputStream = inputStream;
    }

    @Override
    public String getCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public String getContentType() {
        return headers.getFirst("Content-Type");
    }

    @Override
    public int getContentLength() {
        String length = headers.getFirst("Content-Length");
        if (length != null) {
            return Integer.parseInt(length);
        }
        return -1;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return inputStream;
    }
}
