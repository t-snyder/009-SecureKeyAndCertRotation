package svc.crypto;

import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.model.CertificateMessageFactory;


public class AesGcmHkdfCrypto
{
  private static final Logger LOGGER = LoggerFactory.getLogger( AesGcmHkdfCrypto.class );

  private static final int    AES_KEY_LENGTH   = 32; // 256 bits
  private static final int    GCM_IV_LENGTH    = 12; // 96 bits
  private static final int    GCM_TAG_LENGTH   = 16; // 128 bits
  private static final int    HKDF_SALT_LENGTH = 32;
  private static final String HKDF_INFO        = "AES-GCM-HKDF-2024";

  static
  {
    Security.addProvider( new BouncyCastleProvider() );
  }
  
  private final GCMModeCipher      encryptCipher;
  private final GCMModeCipher      decryptCipher;
  private final HKDFBytesGenerator hkdfGenerator;
  private       SecureRandom       secureRandom;

  public AesGcmHkdfCrypto()
  {
    this.encryptCipher = GCMBlockCipher.newInstance( new AESLightEngine() );
    this.decryptCipher = GCMBlockCipher.newInstance( new AESLightEngine() );
    this.hkdfGenerator = new HKDFBytesGenerator( new SHA256Digest() );
    this.secureRandom  = new SecureRandom();
  }

  public void rotateRandom()
  {
    this.secureRandom = new SecureRandom();
  }
  
  /**
   * Instance-based encryption for maximum performance
   */
  public EncryptedData encrypt( byte[] plaintext, byte[] sharedSecret ) 
    throws Exception
  {
    // Generate random salt and IV
    byte[] salt = new byte[ HKDF_SALT_LENGTH ];
    byte[] iv   = new byte[ GCM_IV_LENGTH    ];
 
    secureRandom.nextBytes( salt );
    secureRandom.nextBytes( iv   );

    // Derive key
    byte[] derivedKey = deriveKeyInstance( sharedSecret, salt, HKDF_INFO );

    try
    {
      // Initialize cipher
      AEADParameters parameters = new AEADParameters( new KeyParameter( derivedKey ), GCM_TAG_LENGTH * 8, iv );
      encryptCipher.init( true, parameters );

      // Encrypt
      byte[] output = new byte[encryptCipher.getOutputSize( plaintext.length )];
      int len = encryptCipher.processBytes( plaintext, 0, plaintext.length, output, 0 );
      len += encryptCipher.doFinal( output, len );

      // Split output
      byte[] ciphertext = Arrays.copyOf( output, len - GCM_TAG_LENGTH );
      byte[] tag = Arrays.copyOfRange( output, len - GCM_TAG_LENGTH, len );

      return new EncryptedData( salt, iv, ciphertext, tag );

    } 
    finally
    {
      Arrays.fill( derivedKey, (byte)0 );
    }
  }

  /**
   * Instance-based decryption for maximum performance
   */
  public byte[] decrypt( EncryptedData encryptedData, byte[] sharedSecret ) 
    throws Exception
  {
    byte[] derivedKey = deriveKeyInstance( sharedSecret, encryptedData.getSalt(), HKDF_INFO );
    
 //   LOGGER.info( "AesGcmHkdfCrypto.decrypt - starting" );
    try
    {
      // Initialize cipher
      AEADParameters parameters = new AEADParameters( new KeyParameter( derivedKey ), GCM_TAG_LENGTH * 8, encryptedData.getIv() );
      decryptCipher.init( false, parameters );

      // Combine input
      byte[] input = new byte[encryptedData.getCiphertext().length + encryptedData.getTag().length];
      System.arraycopy( encryptedData.getCiphertext(), 0, input, 0, encryptedData.getCiphertext().length );
      System.arraycopy( encryptedData.getTag(), 0, input, encryptedData.getCiphertext().length, encryptedData.getTag().length );

      // Decrypt
      byte[] output = new byte[decryptCipher.getOutputSize( input.length )];
      int len = decryptCipher.processBytes( input, 0, input.length, output, 0 );
      len += decryptCipher.doFinal( output, len );

      return Arrays.copyOf( output, len );
    } 
    finally
    {
      Arrays.fill( derivedKey, (byte)0 );
    }
  }

  private byte[] deriveKeyInstance( byte[] sharedSecret, byte[] salt, String info )
  {
    HKDFParameters params = new HKDFParameters( sharedSecret, salt, info.getBytes() );
    hkdfGenerator.init( params );

    byte[] derivedKey = new byte[AES_KEY_LENGTH];
    hkdfGenerator.generateBytes( derivedKey, 0, AES_KEY_LENGTH );

    return derivedKey;
  }
  
  /**
   * Performance testing and example usage
   */
  public static void main( String[] args )
  {
    try
    {
      // Simulate a shared secret
      byte[] sharedSecret = "MySharedSecret123456789012345678901234567890".getBytes();
      String message      = "This is a confidential message that needs to be encrypted!";
      byte[] plaintext    = message.getBytes( "UTF-8" );

      System.out.println( "=== AES-GCM HKDF Crypto Performance Test ===" );
      System.out.println( "Original message: " + message );
      System.out.println( "Plaintext length: " + plaintext.length + " bytes\n" );

      // Test instance-based approach
      System.out.println( "--- Instance Method Test ---" );
 
      AesGcmHkdfCrypto crypto    = new AesGcmHkdfCrypto();
      long             startTime = System.nanoTime();

      EncryptedData instanceEncrypted = crypto.encrypt( plaintext,         sharedSecret );
      byte[]        instanceDecrypted = crypto.decrypt( instanceEncrypted, sharedSecret );
      String        instanceMessage   = new String(     instanceDecrypted, "UTF-8" );

      long instanceTime = System.nanoTime() - startTime;
      System.out.println( "Instance method result: " + message.equals( instanceMessage ) );
      System.out.println( "Instance method time: " + ( instanceTime / 1_000_000.0 ) + " ms\n" );

      // Performance results with multiple operations
      System.out.println( "--- Performance Comparison (100000 operations) ---" );
      int iterations = 100000;

      // Instance method performance
      startTime = System.nanoTime();
      for( int i = 0; i < iterations; i++ )
      {
        EncryptedData srcMsg   = crypto.encrypt( plaintext, sharedSecret );
        byte[]        msgBytes = srcMsg.serialize();
        EncryptedData destMsg  = EncryptedData.deserialize( msgBytes );
        crypto.decrypt( destMsg, sharedSecret );
      }
      long instanceTotalTime = System.nanoTime() - startTime;

      System.out.println( "Instance methods total: " + ( instanceTotalTime / 1_000_000.0 ) + " ms" );
    } 
    catch( Exception e )
    {
      System.err.println( "Error: " + e.getMessage() );
      e.printStackTrace();
    }
  }
  
}
