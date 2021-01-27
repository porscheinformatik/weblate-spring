package at.porscheinformatik.weblate;

import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Collections;

/**
 * Adds Weblate authentication information to HTTP requests.
 */
public class WeblateAuthenticationInterceptor implements ClientHttpRequestInterceptor {

  private final String authToken;

  /**
   * @param authToken Weblate API token
   */
  public WeblateAuthenticationInterceptor(String authToken) {
    this.authToken = authToken;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest httpRequest, byte[] bytes,
                                      ClientHttpRequestExecution clientHttpRequestExecution) throws IOException {

    httpRequest.getHeaders().add("Authorization", "Token " + authToken);
    httpRequest.getHeaders().setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

    return clientHttpRequestExecution.execute(httpRequest, bytes);
  }
}