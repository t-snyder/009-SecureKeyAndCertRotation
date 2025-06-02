package svc.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.PublicKey;
import java.util.Arrays;

import javax.crypto.SecretKey;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.exceptions.AvroTransformException;
import svc.utils.AvroSchemaReader;
import svc.utils.AvroUtil;

public class KyberResponder implements Serializable
{
  private static final long   serialVersionUID = -221538974069274551L;
  private static final Logger LOGGER           = LoggerFactory.getLogger( KyberResponder.class );

  private static Schema   msgSchema = null;
  private static AvroUtil avroUtil  = new AvroUtil();

  private String                     svcId        = null;  // Initiator service id
  private PublicKey                  publicKey    = null;  // Initiator generated Public Key
  private SecretKeyWithEncapsulation encSecretKey = null;  // encapsulated secret key
  private SecretKey                  secretKey    = null;  // secret key
  private byte[]                     sharedSecret = null;  // Shared secret used with encrypt and decrypt
  
  /**
   * Constructor for Responder 
   * @param kyberKeyPair
   */
  public KyberResponder( String svcId, PublicKey publicKey )
  {
    this.svcId     = svcId;
    this.publicKey = publicKey;
  }

  /**
   * Constructor for Responder service
   * 
   * @param svcId
   * @param publicKey
   * @param encSecretKey
   */
  public KyberResponder( String svcId, PublicKey publicKey, SecretKeyWithEncapsulation encSecretKey )
  {
    this.svcId        = svcId;
    this.publicKey    = publicKey;
    this.encSecretKey = encSecretKey;
    this.secretKey    = encSecretKey;
    this.sharedSecret = encSecretKey.getEncoded();
  }
  
  public String getSvcId()
  {
    return svcId;
  }

  public PublicKey getPublicKey()
  {
    return publicKey;
  }
  
  public SecretKeyWithEncapsulation getEncSecretKey() 
  { 
    return encSecretKey; 
  }
  
  public SecretKey getSecretKey() 
  {
    return secretKey; 
  }

  public byte[] getSharedSecret()  
  { 
    LOGGER.info( "==============================================================================" );
    LOGGER.info( "KyberInitiator.getSharedSecret = " + Arrays.toString( sharedSecret ));
    return sharedSecret;
  }
 }
