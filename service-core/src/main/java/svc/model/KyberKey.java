package svc.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;

import svc.utils.MLKEMUtils;

public class KyberKey
{
  private String                     svcId            = null;  // Client service id
  private KeyPair                    requestorKeyPair = null;
  private PublicKey                  publicKey        = null;
  private SecretKeyWithEncapsulation encSecretKey     = null;  // encapsulated

  /**
   * Constructor for requestor which generates a new KeyPair
   * @throws InvalidAlgorithmParameterException 
   * @throws NoSuchProviderException 
   * @throws NoSuchAlgorithmException 
   */
  public KyberKey( String svcId ) 
   throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    this.svcId            = svcId;
    this.requestorKeyPair = MLKEMUtils.generateKeyPair();
  }
  
  /**
   * Constructor for Requestor with pregenerated keypair
   * @param kyberKeyPair
   */
  public KyberKey( String svcId, KeyPair kyberKeyPair )
  {
    this.svcId            = svcId;
    this.requestorKeyPair = kyberKeyPair;
  }

  /**
   * Constructor for Receiver service
   * 
   * @param svcId
   * @param publicKey
   * @param encSecretKey
   */
  public KyberKey( String svcId, PublicKey publicKey, SecretKeyWithEncapsulation encSecretKey )
  {
    this.svcId        = svcId;
    this.publicKey    = publicKey;
    this.encSecretKey = encSecretKey;
  }
  
  
  public String                     getSvcId()                 { return svcId;                     }
  public byte[]                     getEncodedSecretKey()      { return encSecretKey.getEncoded(); }
  public SecretKeyWithEncapsulation getEncapsulatedSecretKey() { return encSecretKey;              }

  public PublicKey  getPublicKey() 
  {
    if( requestorKeyPair == null )
      return publicKey;
    
    return requestorKeyPair.getPublic(); 
  }
  
  public PrivateKey getPrivateKey() 
  {
    if( requestorKeyPair == null )
      return null;
    
    return requestorKeyPair.getPrivate(); 
  }

  public byte[] getPublicKeyEncoded()  
  { 
    if( requestorKeyPair != null )
      return MLKEMUtils.encodePublicKey(  requestorKeyPair.getPublic() );
 
    if( publicKey != null )
      return MLKEMUtils.encodePublicKey( publicKey );
    
    return null;
  }
  
  public byte[] getPrivateKeyEncoded() 
  { 
    if( requestorKeyPair != null )
      return MLKEMUtils.encodePrivateKey( requestorKeyPair.getPrivate()); 
    
    return null;
  }

  
  public void setEncapsulatedKey( SecretKeyWithEncapsulation encSecretKey )
  {
    this.encSecretKey = encSecretKey;
  }
  
  public static byte[] serialize( KyberKey key )
   throws IOException
  {
    try( ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream    objectOutputStream    = new ObjectOutputStream( byteArrayOutputStream )) 
    {
      objectOutputStream.writeObject( key );
      return byteArrayOutputStream.toByteArray();
    }
  }
  
  public static KyberKey deSerialize( byte[] keyBytes )
   throws IOException, ClassNotFoundException
  {
    try( ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream( keyBytes );
         ObjectInputStream    objectInputStream    = new ObjectInputStream(byteArrayInputStream) ) 
    {
      return (KyberKey) objectInputStream.readObject();
    }
  }
}
