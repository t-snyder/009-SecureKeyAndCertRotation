package svc.utils;


import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;

import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;

public class DebugCrypto
{
  private static final Logger LOGGER = LoggerFactory.getLogger( DebugCrypto.class );

  public static void printPublicKey( PublicKey publicKey )
  {
    LOGGER.info( "=== PUBLIC KEY DEBUG INFO ===" );
    LOGGER.info( "Algorithm: " + publicKey.getAlgorithm() );
    LOGGER.info( "Format: " + publicKey.getFormat() );

    // Print encoded bytes in hex
    byte[] encoded = publicKey.getEncoded();
    LOGGER.info( "Encoded length: " + encoded.length + " bytes" );
    LOGGER.info( "Encoded (hex): " + Hex.toHexString( encoded ) );

    // Print first/last few bytes for quick visual comparison
    LOGGER.info( "First 16 bytes: " + Hex.toHexString( encoded, 0, Math.min( 16, encoded.length ) ) );
    if( encoded.length > 16 )
    {
      int start = Math.max( 0, encoded.length - 16 );
      LOGGER.info( "Last 16 bytes: " + Hex.toHexString( encoded, start, encoded.length - start ) );
    }

    // Object hash for quick comparison
    LOGGER.info( "Object hash: " + Integer.toHexString( publicKey.hashCode() ) );
    LOGGER.info( "===========================\n" );
  }

  public static void printSecretKeyWithEncapsulation( SecretKeyWithEncapsulation keyWithEncap )
  {
    LOGGER.info( "=== SECRET KEY WITH ENCAPSULATION DEBUG INFO ===" );

    // Encapsulation data (safe to print - this is meant to be transmitted)
    byte[] encapsulation = keyWithEncap.getEncapsulation();

    LOGGER.info( "Secret key algorithm: " + keyWithEncap.getAlgorithm());
    LOGGER.info( "Secret key format:    " + keyWithEncap.getFormat());
    LOGGER.info( "Secret Key length:    " + keyWithEncap.getEncoded().length + " bytes");
    LOGGER.info( "Encapsulation length: " + encapsulation.length + " bytes" );
    LOGGER.info( "Encapsulation (hex):  " + Hex.toHexString( encapsulation ) );
   }
}
