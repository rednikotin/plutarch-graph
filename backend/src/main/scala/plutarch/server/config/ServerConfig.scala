package plutarch.server.config

import java.io.FileNotFoundException
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManagerFactory }
import akka.http.scaladsl.{ ConnectionContext, HttpsConnectionContext }
import com.typesafe.scalalogging.LazyLogging
import scala.util.{ Failure, Try }

object ServerConfig {

  val port = 8080
  val interface = "0.0.0.0"

  object TLS extends LazyLogging {
    val port = 9090

    private def getKeyStore(password: Array[Char]): Try[KeyStore] = {
      val keyStoreFile = "tls/localhost.jks"
      val keystoreInputStream = getClass.getClassLoader.getResourceAsStream(keyStoreFile)

      if (keystoreInputStream == null)
        Failure(new FileNotFoundException(s"Can't find key store file at $keyStoreFile"))
      else
        Try {
          val keyStoreType = "JKS"
          val keyStore = KeyStore.getInstance(keyStoreType)
          keyStore.load(keystoreInputStream, password)
          keyStore
        }
    }

    private def getSslContext(password: Array[Char], keystore: KeyStore): Try[SSLContext] = {
      val keyManagerAlgorithm = "SunX509"

      Try {
        val sslContext = SSLContext.getInstance("TLS")
        val keyManagerFactory = KeyManagerFactory.getInstance(keyManagerAlgorithm)
        keyManagerFactory.init(keystore, password)
        val tmf = TrustManagerFactory.getInstance(keyManagerAlgorithm)
        tmf.init(keystore)
        sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
        sslContext
      }
    }

    private def getTlsKeyStorePassword: Array[Char] = {

      /*val environmentVariableWithPassword = "TLS_KEY_STORE_PASSWORD"

      sys.env.getOrElse(environmentVariableWithPassword, {
        logger.warn("{} is unset", environmentVariableWithPassword)
        "unset"
      }).toCharArray*/

      "9f0ht032fr09fds909SDG$3gt32f#@FDSfs".toCharArray
    }

    def connectionContext: Try[HttpsConnectionContext] = {
      val password = getTlsKeyStorePassword

      getKeyStore(password)
        .flatMap(keyStore ⇒ getSslContext(password, keyStore))
        .map(sslContext ⇒ ConnectionContext.https(sslContext))
    }
  }

}