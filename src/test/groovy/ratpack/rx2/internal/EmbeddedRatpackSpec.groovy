/*
 * Copyright 2012 the original author or authors.
 *
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

package ratpack.rx2.internal

import io.netty.util.CharsetUtil
import io.netty.util.ResourceLeakDetectorFactory
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import ratpack.http.client.RequestSpec
import ratpack.rx2.internal.spock.InheritedTimeout
import ratpack.rx2.internal.spock.InheritedUnroll
import ratpack.test.embed.EmbeddedApp
import ratpack.test.http.TestHttpClient
import spock.lang.Specification

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

@InheritedTimeout(30)
@InheritedUnroll
abstract class EmbeddedRatpackSpec extends Specification {

  private static final AtomicReference<Boolean> LEAKED = new AtomicReference<>(false)

  static {
    System.setProperty("io.netty.leakDetectionLevel", "paranoid")
    ResourceLeakDetectorFactory.resourceLeakDetectorFactory = new FlaggingResourceLeakDetectorFactory(LEAKED)
  }
  @Rule
  TemporaryFolder temporaryFolder

  @Delegate
  TestHttpClient client

  boolean failOnLeak = true

  abstract EmbeddedApp getApplication()

  void configureRequest(RequestSpec requestSpecification) {
    // do nothing
  }

  def setup() {
    LEAKED.set(false)
    client = testHttpClient({ application.address }) {
      configureRequest(it)
    }
  }

  def cleanup() {
    try {
      application.server.stop()
    } catch (Throwable ignore) {

    }

    if (LEAKED.get() && failOnLeak) {
      throw new Exception("A resource has leaked in this test!")
    }
  }

  String rawResponse(Charset charset = CharsetUtil.UTF_8) {
    StringBuilder builder = new StringBuilder()
    Socket socket = new Socket(application.address.host, application.address.port)
    try {
      new OutputStreamWriter(socket.outputStream, "UTF-8").with {
        write("GET / HTTP/1.1\r\n")
        write("Connection: close\r\n")
        write("\r\n")
        flush()
      }

      InputStreamReader inputStreamReader = new InputStreamReader(socket.inputStream, charset)
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader)

      def chunk
      while ((chunk = bufferedReader.readLine()) != null) {
        builder.append(chunk).append("\n")
      }

      builder.toString()
    } finally {
      socket.close()
    }
  }

}