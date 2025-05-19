package svc.model;


import io.fabric8.kubernetes.api.model.Secret;
//import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating certificate messages from Kubernetes secrets. This
 * class handles the extraction of certificate data from K8s secrets and
 * creation of CertificateMessage objects.
 */
public class CertificateMessageFactory
{

  private static final Logger LOGGER = LoggerFactory.getLogger( CertificateMessageFactory.class );

  // Secret key for encryption 
  private final byte[] encryptionKey;

  /**
   * Constructor
   * 
   * @param encryptionKey
   *          The key to use for message encryption
   */
  public CertificateMessageFactory( byte[] encryptionKey )
  {
    if( encryptionKey == null )
    {
      throw new IllegalArgumentException( "Encryption key cannot be null or empty" );
    }
    this.encryptionKey = encryptionKey;
  }

  /**
   * Creates an encrypted message from a Kubernetes secret
   * 
   * @param secret
   *          The Kubernetes secret containing certificate data
   * @param eventType
   *          The type of event (INITIAL, ADDED, MODIFIED, DELETED)
   * @return Encrypted message as byte array
   * @throws Exception
   *           If message creation fails
   */
  public byte[] createFromSecret( Secret secret, CertificateMessage.CertEventType eventType ) 
    throws Exception
  {
    if( secret == null || secret.getMetadata() == null )
    {
      throw new IllegalArgumentException( "Secret cannot be null" );
    }

    Map<String, String> data = secret.getData();
    if( data == null )
    {
      throw new IllegalArgumentException( "Secret data is null" );
    }

    // Extract certificate data from the secret
    String caCert  = extractCertificate( data, "ca.crt" );
    String tlsCert = extractCertificate( data, "tls.crt" );

    if( tlsCert == null )
    {
      LOGGER.warn( "TLS certificate not found in secret: {}", secret.getMetadata().getName() );
    }

    // Get metadata
    String secretName = secret.getMetadata().getName();
    String namespace  = secret.getMetadata().getNamespace();

    // Create and encrypt the message
    return CertificateMessage.createEncryptedMessage( eventType, caCert, tlsCert, secretName, namespace, encryptionKey );
  }

  /**
   * Helper method to extract certificate data from secret data map
   * 
   * @param data
   *          The secret data map
   * @param key
   *          The key for the certificate (e.g., "ca.crt", "tls.crt")
   * @return The certificate data as a string, or null if not found
   */
  private String extractCertificate( Map<String, String> data, String key )
  {
    if( !data.containsKey( key ) )
    {
      LOGGER.debug( "Certificate key '{}' not found in secret data", key );
      return null;
    }

    // The certificate is already Base64 encoded in the Secret
    // We return it as is since we'll use it encoded in our message
    return data.get( key );
  }

  /**
   * Creates an initial certificate message
   * 
   * @param secret
   *          The Kubernetes secret
   * @return Encrypted message as byte array
   * @throws Exception
   *           If message creation fails
   */
  public byte[] createInitialMessage( Secret secret ) throws Exception
  {
    return createFromSecret( secret, CertificateMessage.CertEventType.INITIAL );
  }

  /**
   * Creates an added certificate message
   * 
   * @param secret
   *          The Kubernetes secret
   * @return Encrypted message as byte array
   * @throws Exception
   *           If message creation fails
   */
  public byte[] createAddedMessage( Secret secret ) throws Exception
  {
    return createFromSecret( secret, CertificateMessage.CertEventType.ADDED );
  }

  /**
   * Creates a modified certificate message
   * 
   * @param secret
   *          The Kubernetes secret
   * @return Encrypted message as byte array
   * @throws Exception
   *           If message creation fails
   */
  public byte[] createModifiedMessage( Secret secret ) throws Exception
  {
    return createFromSecret( secret, CertificateMessage.CertEventType.MODIFIED );
  }

  /**
   * Creates a deleted certificate message
   * 
   * @param secret
   *          The Kubernetes secret
   * @return Encrypted message as byte array
   * @throws Exception
   *           If message creation fails
   */
  public byte[] createDeletedMessage( Secret secret ) throws Exception
  {
    return createFromSecret( secret, CertificateMessage.CertEventType.DELETED );
  }

  /**
   * Decrypt and read a message
   * 
   * @param encryptedData
   *          The encrypted message data
   * @return The decrypted CertificateMessage
   */
  public CertificateMessage readMessage( byte[] encryptedData )
  {
    return CertificateMessage.decryptMessage( encryptedData, encryptionKey );
  }
}