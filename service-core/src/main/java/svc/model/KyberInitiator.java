package svc.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.crypto.KyberKEMCrypto;

public class KyberInitiator implements Serializable
{
  private static final long serialVersionUID = -2215389761069274551L;

  private static final Logger LOGGER = LoggerFactory.getLogger( KyberInitiator.class );

  private String    svcId        = null;  // Initiator service id
  private KeyPair   keyPair      = null;  // Initiator generated key pair
  private SecretKey secretKey    = null;  // Generated from responder encapsulation and initiator private key
  private byte[]    sharedSecret = null;  // Used to encrypt and decrypt between initiator and responder services.
 
  /**
   * Constructor for initiator which generates a new KeyPair
 
   * @throws InvalidAlgorithmParameterException 
   * @throws NoSuchProviderException 
   * @throws NoSuchAlgorithmException 
   */
  public KyberInitiator( String svcId ) 
   throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    this.svcId   = svcId;
    this.keyPair = KyberKEMCrypto.generateKeyPair();
  }
  
  /**
   * Constructor for Initiator with pregenerated keypair
   * @param kyberKeyPair
   */
  public KyberInitiator( String svcId, KeyPair kyberKeyPair )
  {
    this.svcId   = svcId;
    this.keyPair = kyberKeyPair;
  }

  public String getSvcId()
  {
    return svcId;
  }

  public KeyPair getKeyPair()
  {
    if( keyPair == null )
      return null;
    
    return keyPair; 
  }
  
  public PublicKey getPublicKey() 
  {
    if( keyPair == null )
      return null;
    
    return keyPair.getPublic(); 
  }
  
  public PrivateKey getPrivateKey() 
  {
    if( keyPair == null )
      return null;
    
    return keyPair.getPrivate(); 
  }

  public SecretKey getSecretKey() 
  { 
    LOGGER.info( "==============================================================================" );
    LOGGER.info( "KyberInitiator.getSecretKey = " + Arrays.toString( secretKey.getEncoded() ));
    return secretKey; 
  }
  
  public byte[] getSharedSecret() 
  { 
    LOGGER.info( "==============================================================================" );
    LOGGER.info( "KyberInitiator.getSharedSecret = " + Arrays.toString( sharedSecret ));
    return sharedSecret; 
  }

  public byte[] getPublicKeyEncoded()  
  { 
    if( keyPair != null )
      return KyberKEMCrypto.encodePublicKey(  keyPair.getPublic() );
    
    return null;
  }
  
  public byte[] getPrivateKeyEncoded() 
  { 
    if( keyPair != null )
      return KyberKEMCrypto.encodePrivateKey( keyPair.getPrivate()); 
    
    return null;
  }

  public void setSharedSecret( byte[] keyBytes ) 
  {
    this.sharedSecret = keyBytes;
    this.secretKey    = new SecretKeySpec( keyBytes, "AES" );
  }
  
  public static byte[] serialize( KyberInitiator key )
   throws IOException
  {
    try( ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream    objectOutputStream    = new ObjectOutputStream( byteArrayOutputStream )) 
    {
      objectOutputStream.writeObject( key );
      return byteArrayOutputStream.toByteArray();
    }
  }
  
  public static KyberInitiator deSerialize( byte[] keyBytes )
   throws IOException, ClassNotFoundException
  {
    try( ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream( keyBytes );
         ObjectInputStream    objectInputStream    = new ObjectInputStream(byteArrayInputStream) ) 
    {
      return (KyberInitiator) objectInputStream.readObject();
    }
  }
}
