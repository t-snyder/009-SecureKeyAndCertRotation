package helper;


import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.FileInputStream;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;


public final class SSLContextBuilder
{
  public static SSLContext build( String certFilePath )
  {
    try
    {
      // Load the TLS certificate
      FileInputStream    fis  = new FileInputStream( certFilePath );
      CertificateFactory cf   = CertificateFactory.getInstance("X.509");
      Certificate        cert = cf.generateCertificate( fis );

      // Create a KeyStore containing the trusted certificate
      KeyStore keyStore = KeyStore.getInstance( "PKCS12" );
      keyStore.load( null, null ); // Initialize with no password
      keyStore.setCertificateEntry( "alias", cert );
      
      // Initialize a TrustManagerFactory with the KeyStore
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance( TrustManagerFactory.getDefaultAlgorithm() );
      trustManagerFactory.init( keyStore );

      // Initialize the SSLContext with the TrustManager
      SSLContext sslContext = SSLContext.getInstance( "TLS" );
      sslContext.init( null, trustManagerFactory.getTrustManagers(), null );

      // Now you can use the sslContext for your SSL connections
      System.out.println( "SSLContext created successfully!" );
      return sslContext;
    } 
    catch( Exception e )
    {
      e.printStackTrace();
    }

    return null;
  }
}
