package svc.model;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
//import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
//import java.util.Base64;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A secure message container for transporting certificate data between
 * services. This class supports serialization to byte arrays and
 * encryption/decryption.
 */
public class CertificateMessage implements Serializable
{
  private static final long   serialVersionUID = 3844367375910100379L;
  private static final Logger LOGGER           = LoggerFactory.getLogger( CertificateMessage.class );

  // AES-GCM parameters
  private static final int GCM_IV_LENGTH   = 12;
  private static final int GCM_TAG_LENGTH = 128;

  // HKDF parameters
  private static final int SALT_LENGTH = 32;
  private static final int SESSION_ID_LENGTH = 16;
  private static final int KEY_LENGTH = 32; // 256 bits
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  
  // Format version to aid in future compatibility
  private static final byte FORMAT_VERSION = 1;

  // Certificate event types
  public enum CertEventType
  {
    INITIAL, ADDED, MODIFIED, DELETED
  }

  // Message fields
  private String        id;
  private long          timestamp;
  private CertEventType eventType;
  private String        caCertificate; // Base64 encoded CA certificate
  private String        tlsCertificate; // Base64 encoded TLS certificate
  private String        secretName; // Name of the Kubernetes secret
  private String        namespace; // Kubernetes namespace

  // Encryption metadata (not serialized)
//  private transient byte[] iv;

  /**
   * Default constructor for serialization
   */
  public CertificateMessage()
  {
    this.id        = UUID.randomUUID().toString();
    this.timestamp = Instant.now().toEpochMilli();
  }

  /**
   * Constructor with event type and certificates
   * 
   * @param eventType  - The type of certificate event
   * @param caCert     - The CA certificate data
   * @param tlsCert    - The TLS certificate data
   * @param secretName - The name of the Kubernetes secret
   * @param namespace  - The Kubernetes namespace
   */
  public CertificateMessage( CertEventType eventType, String caCert, String tlsCert, String secretName, String namespace )
  {
    this();
    this.eventType      = eventType;
    this.caCertificate  = caCert;
    this.tlsCertificate = tlsCert;
    this.secretName     = secretName;
    this.namespace      = namespace;
  }

  /**
   * Serializes the message to a byte array
   * 
   * @return Serialized message as byte array
   * @throws IOException If serialization fails
   */
  public byte[] toByteArray() 
   throws IOException
  {
    try( ByteArrayOutputStream baos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream( baos ) )
    {
      oos.writeObject( this );
      return baos.toByteArray();
    }
  }

  /**
   * Deserializes a byte array back to a CertificateMessage
   * 
   * @param data The serialized message data
   * @return     The deserialized CertificateMessage
   * @throws IOException If deserialization fails
   * @throws ClassNotFoundException If the class cannot be found
   */
  public static CertificateMessage fromByteArray( byte[] data ) 
   throws IOException, ClassNotFoundException
  {
    try( ByteArrayInputStream bais = new ByteArrayInputStream( data ); 
         ObjectInputStream    ois  = new ObjectInputStream(    bais ) )
    {
      return (CertificateMessage)ois.readObject();
    }
  }

  /**
   * Implements HKDF (HMAC-based Key Derivation Function) according to RFC 5869
   * 
   * @param ikm Input Key Material (the master key)
   * @param salt Salt value (a non-secret random value)
   * @param info Context and application specific information
   * @param length Length of the output key material
   * @return The derived key
   * @throws NoSuchAlgorithmException If HMAC algorithm is not available
   * @throws InvalidKeyException If the key spec is invalid
   */
  private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) 
   throws NoSuchAlgorithmException, InvalidKeyException {
      // Step 1: Extract
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      if (salt == null || salt.length == 0) {
          salt = new byte[mac.getMacLength()];
      }
      SecretKeySpec saltKey = new SecretKeySpec(salt, HMAC_ALGORITHM);
      mac.init(saltKey);
      byte[] prk = mac.doFinal(ikm);
      
      // Step 2: Expand
      int iterations = (int) Math.ceil((double) length / mac.getMacLength());
      byte[] mixin = new byte[0];
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      
      for (int i = 1; i <= iterations; i++) {
          mac.reset();
          mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));
          mac.update(mixin);
          mac.update(info);
          mac.update((byte) i);
          mixin = mac.doFinal();
          result.write(mixin, 0, Math.min(mixin.length, length - result.size()));
      }
      
      return result.toByteArray();
  }
  
  /**
   * Encrypts the serialized message using AES-GCM
   * 
   * @param serializedMessage  The serialized message to encrypt
   * @param secretKey          The secret key for encryption
   * @return                   The encrypted message
   * @throws Exception         If encryption fails
   */
  public static byte[] encrypt( byte[] serializedMessage, byte[] masterKeyBytes ) 
   throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
  {
    SecureRandom secureRandom = new SecureRandom();
    
    // Generate salt for HKDF
    byte[] salt = new byte[SALT_LENGTH];
    secureRandom.nextBytes(salt);
    
    // Generate unique session ID to prevent replay attacks
    byte[] sessionId = new byte[SESSION_ID_LENGTH];
    secureRandom.nextBytes(sessionId);
    
    // Generate IV for AES-GCM
    byte[] iv = new byte[GCM_IV_LENGTH];
    secureRandom.nextBytes(iv);
    
    // Info for HKDF - combine session ID with a fixed string
    byte[] info = ByteBuffer.allocate(SESSION_ID_LENGTH + 8)
            .put(sessionId)
            .put("PFS-CERT".getBytes(StandardCharsets.UTF_8))
            .array();
    
    // Derive encryption key using HKDF
    byte[] derivedKey = hkdf( masterKeyBytes, salt, info, KEY_LENGTH );
    SecretKey aesKey = new SecretKeySpec(derivedKey, "AES");
    
    // Initialize cipher
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
    cipher.init(Cipher.ENCRYPT_MODE, aesKey, parameterSpec);
    
    // Add session ID as Additional Authenticated Data (AAD)
    cipher.updateAAD(sessionId);
    
    // Encrypt
    byte[] cipherText = cipher.doFinal(serializedMessage);
    
    // Combine all components into final message
    // Format: VERSION(1) | SALT(32) | SESSION_ID(16) | IV(12) | CIPHERTEXT
    ByteBuffer result = ByteBuffer.allocate(1 + salt.length + sessionId.length + iv.length + cipherText.length);
    result.put(FORMAT_VERSION);
    result.put(salt);
    result.put(sessionId);
    result.put(iv);
    result.put(cipherText);
    
    return result.array();
 }

  /**
   * Decrypts an encrypted message
   * 
   * @param encryptedMessage
   *          The encrypted message
   * @param secretKey
   *          The secret key for decryption
   * @return The decrypted message
   * @throws Exception
   *           If decryption fails
   */
  public static byte[] decrypt( byte[] encryptedMessage, byte[] masterKeyBytes ) 
   throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException
  {
    // Check format version
    if (encryptedMessage[0] != FORMAT_VERSION) 
    {
      throw new IllegalArgumentException("Unsupported message format version: " + encryptedMessage[0]);
    }
    
    // Extract components from the encrypted message
    ByteBuffer buffer = ByteBuffer.wrap(encryptedMessage);
    buffer.get(); // Skip the version byte
    
    byte[] salt = new byte[SALT_LENGTH];
    buffer.get(salt);
    
    byte[] sessionId = new byte[SESSION_ID_LENGTH];
    buffer.get(sessionId);
    
    byte[] iv = new byte[GCM_IV_LENGTH];
    buffer.get(iv);
    
    // Extract ciphertext (the rest of the buffer)
    byte[] cipherText = new byte[buffer.remaining()];
    buffer.get(cipherText);
     
    // Info for HKDF - combine session ID with a fixed string
    byte[] info = ByteBuffer.allocate(SESSION_ID_LENGTH + 8)
            .put(sessionId)
            .put("PFS-CERT".getBytes(StandardCharsets.UTF_8))
            .array();
    
    // Derive decryption key using HKDF
    byte[]    derivedKey = hkdf( masterKeyBytes, salt, info, KEY_LENGTH);
    SecretKey aesKey     = new SecretKeySpec( derivedKey, "AES" );
    
    // Initialize cipher
    Cipher           cipher        = Cipher.getInstance("AES/GCM/NoPadding");
    GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
    cipher.init(Cipher.DECRYPT_MODE, aesKey, parameterSpec);
    
    // Add session ID as Additional Authenticated Data (AAD)
    cipher.updateAAD(sessionId);
    
    // Decrypt
    return cipher.doFinal(cipherText); 
  }

  /**
   * Helper method to create a certificate message and encrypt it for transport
   * with Perfect Forward Secrecy
   * 
   * @param eventType The event type
   * @param caCert The CA certificate
   * @param tlsCert The TLS certificate
   * @param secretName The name of the Kubernetes secret
   * @param namespace The Kubernetes namespace
   * @param masterKey The master encryption key
   * @return The encrypted message as a byte array
   */
  public static byte[] createEncryptedMessage( CertEventType eventType, String caCert, String tlsCert, 
                                               String secretName, String namespace, byte[] masterKey) 
  {
    try 
    {
      CertificateMessage message = new CertificateMessage(eventType, caCert, tlsCert, secretName, namespace);
      byte[] serialized = message.toByteArray();
      return encrypt(serialized, masterKey);
    } 
    catch( Exception e ) 
    {
      LOGGER.error("Failed to create encrypted certificate message", e);
      throw new RuntimeException("Message encryption failed", e);
    }
  }

  /**
   * Helper method to decrypt and deserialize a certificate message
   * 
   * @param encryptedData The encrypted message data
   * @param masterKey The master decryption key
   * @return The decrypted CertificateMessage object
   */
  public static CertificateMessage decryptMessage(byte[] encryptedData, byte[] masterKey ) 
  {
    try 
    {
      byte[] decrypted = decrypt( encryptedData, masterKey );
      return fromByteArray(decrypted);
    } 
    catch( Exception e ) 
    {
      LOGGER.error("Failed to decrypt certificate message", e);
      throw new RuntimeException("Message decryption failed", e);
    }
  }

  // Getters and setters

  public String getId()
  {
    return id;
  }

  public void setId( String id )
  {
    this.id = id;
  }

  public long getTimestamp()
  {
    return timestamp;
  }

  public void setTimestamp( long timestamp )
  {
    this.timestamp = timestamp;
  }

  public CertEventType getEventType()
  {
    return eventType;
  }

  public void setEventType( CertEventType eventType )
  {
    this.eventType = eventType;
  }

  public String getCaCertificate()
  {
    return caCertificate;
  }

  public void setCaCertificate( String caCertificate )
  {
    this.caCertificate = caCertificate;
  }

  public String getTlsCertificate()
  {
    return tlsCertificate;
  }

  public void setTlsCertificate( String tlsCertificate )
  {
    this.tlsCertificate = tlsCertificate;
  }

  public String getSecretName()
  {
    return secretName;
  }

  public void setSecretName( String secretName )
  {
    this.secretName = secretName;
  }

  public String getNamespace()
  {
    return namespace;
  }

  public void setNamespace( String namespace )
  {
    this.namespace = namespace;
  }

  @Override
  public String toString()
  {
    return "CertificateMessage [id=" + id + ", timestamp=" + timestamp + ", eventType=" + eventType + ", secretName=" + secretName + ", namespace=" + namespace + "]";
  }
}