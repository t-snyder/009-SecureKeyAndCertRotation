/**
 * The inspiration for this code (and much of the code) was obtained from 
 * CRYSTALS Kyber for Post-Quantum Hybrid Encryption with Java
 * by Udara Pathum
 *
 * https://medium.com/@hwupathum/using-crystals-kyber-kem-for-hybrid-encryption-with-java-0ab6c70d41fc
 */

package svc.utils;


import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;

import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec;

import javax.crypto.Cipher;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;


/**
 * This code is used by both the Server and the client. It is performed in the
 * following steps. 
 * 
 * 1. The client ( pulsarWatcher ) generates a Kyber keypair using the generateKeyPair( int securityLevel ) method. 
 * 
 * 2. The client ( pulsarWatcher ) sends the publicKey to the Server ( metadataSevc ). This is external to this class.
 * 3. The server ( metadataSvc ) uses the publicKey to generate a shared secret (AES-256) and encapsulate it. 
 * 4. The server ( metadataSvc ) sends the encapsulated key back to the client ( pulsarWatcher ). External to this class. 
 * 5. The client ( pulsarWatcher ) extracts the encapsulated key into a SecretKeyWithEncapsulation using generateSecretKeyReciever().
 */
public class MLKEMUtils
{
  public static final AlgorithmParameterSpec KEM_PARAMETER_SPEC = KyberParameterSpec.kyber1024;
  private static final String PROVIDER             = "BCPQC";
  private static final String KEM_ALGORITHM        = "Kyber";
  private static final String ENCRYPTION_ALGORITHM = "AES";
  private static final String CIPHER_TYPE          = "AES/GCM/NoPadding"; 
//  private static final int    GCM_TAG_LENGTH = 16;
//  private static final String HKDF_INFO      = "MLKEM-AES-KEY";

  static
  {
    // Register Bouncy Castle providers if not already registered
    if( Security.getProvider( "BC" ) == null )
    {
      Security.addProvider( new BouncyCastleProvider() );
    }
    if( Security.getProvider( "BCPQC" ) == null )
    {
      Security.addProvider( new BouncyCastlePQCProvider() );
    }
  }

  /**
   * Generates a Kyber (ML-KEM) key pair.
   * 
   * @param securityLevel
   *          Security level: 512, 768, or 1024
   * @return A new Kyber KeyPair
   */
  public static KeyPair generateKeyPair() 
   throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance( KEM_ALGORITHM, PROVIDER );
    keyPairGenerator.initialize( KEM_PARAMETER_SPEC, new SecureRandom() );

    return keyPairGenerator.generateKeyPair();
  }

  public static SecretKeyWithEncapsulation generateSecretKeySender( PublicKey publicKey ) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    KeyGenerator keyGenerator = KeyGenerator.getInstance( KEM_ALGORITHM, PROVIDER );
    KEMGenerateSpec kemGenerateSpec = new KEMGenerateSpec( publicKey, "Secret", 256 );

    keyGenerator.init( kemGenerateSpec );

    return (SecretKeyWithEncapsulation)keyGenerator.generateKey();
  }

  public static SecretKeyWithEncapsulation generateSecretKeyReceiver( PrivateKey privateKey, byte[] encapsulation ) 
   throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    KEMExtractSpec kemExtractSpec = new KEMExtractSpec( privateKey, encapsulation, "Secret", 256 );
    KeyGenerator keyGenerator = KeyGenerator.getInstance( KEM_ALGORITHM, PROVIDER );

    keyGenerator.init( kemExtractSpec );

    return (SecretKeyWithEncapsulation)keyGenerator.generateKey();
  }

  /**
   * 
   * @param plainText
   * @param key  - Obtained via SecretKeyWtihEncapsulation.getEncoded() returned from method generateSecretKeySender()
   * @return
   * @throws Exception
  public static String encrypt( String plainText, byte[] key ) 
   throws Exception
  {
    SecretKeySpec secretKey = new SecretKeySpec( key, ENCRYPTION_ALGORITHM );
    Cipher cipher = Cipher.getInstance( MODE_PADDING );
    cipher.init( Cipher.ENCRYPT_MODE, secretKey );
    byte[] encryptedBytes = cipher.doFinal( plainText.getBytes() );
    return Base64.getEncoder().encodeToString( encryptedBytes );
  }
 */
 
  
  /**
   * Encrypt with AES/GCM
   * @param plainText
   * @param key  - Obtained via SecretKeyWtihEncapsulation.getEncoded() returned from method generateSecretKeySender()
   * @return
   * @throws Exception
  */
  public static String encrypt( String plainText, byte[] key ) 
   throws Exception
  {
    SecretKeySpec secretKey = new SecretKeySpec( key, ENCRYPTION_ALGORITHM );
    Cipher        cipher    = Cipher.getInstance( CIPHER_TYPE );
    SecureRandom  random    = new SecureRandom();
    byte[]        iv        = new byte[12]; // GCM requires a 12-byte IV
   
    random.nextBytes(iv);
    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv); // 128-bit authentication tag
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

    // Encrypt the plaintext
    byte[] ciphertext = cipher.doFinal( plainText.getBytes() );
  
    // Combine IV and ciphertext
    byte[] encryptedBytes = new byte[iv.length + ciphertext.length];
    System.arraycopy( iv, 0, encryptedBytes, 0, iv.length);
    System.arraycopy( ciphertext, 0, encryptedBytes, iv.length, ciphertext.length);

    return Base64.getEncoder().encodeToString( encryptedBytes );
  }

  /**
   * 
   * @param encryptedText
   * @param key - Obtained via SecretKeyWtihEncapsulation.getEncoded() returned from method generateSecretKeyReceiver()
   * @return
   * @throws Exception
  public static String decrypt( String encryptedText, byte[] key ) 
   throws Exception
  {
    SecretKeySpec secretKey = new SecretKeySpec( key, ENCRYPTION_ALGORITHM );
    Cipher cipher = Cipher.getInstance( MODE_PADDING );
    cipher.init( Cipher.DECRYPT_MODE, secretKey );
    byte[] decodedBytes = Base64.getDecoder().decode( encryptedText );
    byte[] decryptedBytes = cipher.doFinal( decodedBytes );
    return new String( decryptedBytes );
  }
   */

  public static byte[] decrypt(byte[] encryptedData, byte[] keyBytes ) 
   throws Exception 
  {
    SecretKeySpec secretKey = new SecretKeySpec( keyBytes, ENCRYPTION_ALGORITHM );
    Cipher        cipher    = Cipher.getInstance( CIPHER_TYPE );

    // Extract IV from the encrypted data
    byte[] iv = new byte[12];
    System.arraycopy( encryptedData, 0, iv, 0, iv.length );

    // Extract ciphertext from the encrypted data
    byte[] ciphertext = new byte[encryptedData.length - iv.length];
    System.arraycopy( encryptedData, iv.length, ciphertext, 0, ciphertext.length );

    GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

    // Decrypt the ciphertext
    return cipher.doFinal( ciphertext );
  }

 
  public static byte[] encodePublicKey( PublicKey publicKey )
  {
//    return Base64.getEncoder().encodeToString( publicKey.getEncoded() );
    return Base64.getEncoder().encode( publicKey.getEncoded() );
  }

  public static byte[] encodePrivateKey( PrivateKey privateKey )
  {
    return Base64.getEncoder().encode( privateKey.getEncoded() );
  }
  
  public static PublicKey decodePublicKey( byte[] encodedKey ) throws Exception
  {
    byte[]             keyBytes   = Base64.getDecoder().decode( encodedKey );
    KeyFactory         keyFactory = KeyFactory.getInstance( KEM_ALGORITHM, PROVIDER );
    X509EncodedKeySpec keySpec    = new X509EncodedKeySpec( keyBytes );
 
    return keyFactory.generatePublic( keySpec );
  }

  public static PrivateKey decodePrivateKey( byte[] encodedKey ) throws Exception
  {
    byte[]              keyBytes   = Base64.getDecoder().decode( encodedKey );
    KeyFactory          keyFactory = KeyFactory.getInstance( KEM_ALGORITHM, PROVIDER );
    PKCS8EncodedKeySpec keySpec    = new PKCS8EncodedKeySpec( keyBytes );
 
    return keyFactory.generatePrivate( keySpec );
  }
  
  public static SecretKeyWithEncapsulation processKyberExchangeRequest( byte[] data )
   throws Exception
  {
    byte[]    decodedBytes  = Base64.getDecoder().decode( data );
       
    PublicKey                  publicKey     = MLKEMUtils.decodePublicKey( decodedBytes );
    SecretKeyWithEncapsulation encapsulation = MLKEMUtils.generateSecretKeySender( publicKey );
       
    return encapsulation;
  }
}