package svc.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.exceptions.AvroTransformException;
import svc.utils.AvroSchemaReader;
import svc.utils.AvroUtil;


/**
 * Transport object for kyber key exchange and key rotation
 */
public class KyberExchangeMessage implements Serializable
{
  private static final long   serialVersionUID = 1L;
  private static final Logger LOGGER = LoggerFactory.getLogger( KyberExchangeMessage.class );
//  private static final String SCHEMA_PATH   = "/etc/avro-schemas/";

  private static final String SERVICE_ID    = "svcId";
  private static final String EVENT_TYPE    = "eventType";
  private static final String PUBLIC_KEY    = "publicKey";
  private static final String ENCAPSULATION = "encapsulation";
  
  private String svcId         = null; // Client service id that exchange request is going to.
  private String eventType     = null;
  private byte[] publicKey     = null;
  private byte[] encapsulation = null;

  private static Schema   msgSchema = null;
  private static AvroUtil avroUtil  = new AvroUtil();
  
  
  /**
   * Used for exchange request
   * @param svcId
   * @param eventType
   * @param publicKey
   */
  public KyberExchangeMessage( String svcId, String eventType, byte[] publicKey )
  {
    this.svcId     = svcId;        // Client service id
    this.eventType = eventType;
    this.publicKey = publicKey;
  }

  /**
   * Used for exchange resposne
   * @param svcId
   * @param eventType
   * @param publicKey
   * @param encapsulation
   */
  public KyberExchangeMessage( String svcId, String eventType, byte[] publicKey, byte[] encapsulation )
  {
    this.svcId         = svcId;        // Client service id
    this.eventType     = eventType;
    this.publicKey     = publicKey;
    this.encapsulation = encapsulation;
  }

  public String getSvcId()         { return svcId;         }
  public String getEventType()     { return eventType;     }
  public byte[] getPublicKey()     { return publicKey;     }
  public byte[] getEncapsulation() { return encapsulation; }
  
  /**
   * Serialize using Avro binary format
   * @throws Exception 
   */
  public static byte[] serialize( KyberExchangeMessage msgObj )
   throws Exception 
  {
    LOGGER.info( "KyberKeyExchangeMessage.serialize starting." );
    
    if( msgSchema == null )
      msgSchema = AvroSchemaReader.getSchema( "KyberExchangeMessage" ); 

    if( msgSchema == null )
    {
      LOGGER.error( "KeyExchangeMessage serialize could not obtain schema = KyberExchxchagneMessage" );
      throw new Exception( "Avro schema not found" );
    }

    LOGGER.info( "KyberKeyExchangeMessage.serialize found msgSchema." );

    try
    {
      ByteArrayOutputStream             out     = new ByteArrayOutputStream();
      Encoder                           encoder = EncoderFactory.get().binaryEncoder( out, null );
      GenericDatumWriter<GenericRecord> writer  = new GenericDatumWriter<GenericRecord>( msgSchema );
      GenericRecord                     actRec = (GenericRecord) new GenericData.Record( msgSchema );

      if( msgObj.getSvcId()         != null ) actRec.put( SERVICE_ID,    new Utf8(        msgObj.getSvcId()         ));  
      if( msgObj.getEventType()     != null ) actRec.put( EVENT_TYPE,    new Utf8(        msgObj.getEventType()     ));  
      if( msgObj.getPublicKey()     != null ) actRec.put( PUBLIC_KEY,    ByteBuffer.wrap( msgObj.getPublicKey()     ));  
      if( msgObj.getEncapsulation() != null ) actRec.put( ENCAPSULATION, ByteBuffer.wrap( msgObj.getEncapsulation() ));  

      if( msgObj.getEncapsulation() == null )
      {
        LOGGER.info( "KyberExchangeMessage.serialize found null encapsulation." );
      }
      
      try
      {
        writer.write( actRec, encoder );
        encoder.flush();
      } 
      catch( IOException e )
      {
        String msg = "Error serializing KyberKeyMessage. Error = " + e.getMessage();
        LOGGER.error( msg );
        throw new AvroTransformException( msg );
      }
      
      LOGGER.info( "KyberExchangeMessage serialization complete" );
    
      return out.toByteArray();
    } 
    catch( Exception e )
    {
      String msg = "Error serializing KyberKeyMessage. Error = " + e.getMessage();
      LOGGER.error( msg );
      throw new AvroTransformException( msg );
    }
  }

  public static KyberExchangeMessage deSerialize( byte[] bytes ) 
   throws Exception
  {
    LOGGER.info( "Start KyberExchangeMessage deserialize" );
    
    if( msgSchema == null )
      msgSchema = AvroSchemaReader.getSchema( "KyberExchangeMessage" ); 

    if( msgSchema == null )
    {
      LOGGER.error( "KeyExchangeMessage serialize could not obtain schema = KyberExchxchagneMessage" );
      throw new Exception( "Avro schema not found" );
    }

    LOGGER.info( "KyberKeyExchangeMessage.serialize found msgSchema." );

    try
    {
      KyberExchangeMessage msg = null;

      GenericDatumReader<GenericRecord> reader      = null;
      ByteArrayInputStream              inputStream = new ByteArrayInputStream( bytes );
      Decoder                           decoder     = DecoderFactory.get().binaryDecoder( inputStream, null );

      reader = new GenericDatumReader<GenericRecord>( msgSchema );
         
       while( true )
       {
         try
         {
           GenericRecord result = reader.read( null, decoder );
 
           String svcId         = avroUtil.getString(    result, SERVICE_ID    );
           String eventType     = avroUtil.getString(    result, EVENT_TYPE    );
           byte[] publicKey     = avroUtil.getByteArray( result, PUBLIC_KEY    );
           byte[] encapsulation = avroUtil.getByteArray( result, ENCAPSULATION );
 
           msg = new KyberExchangeMessage( svcId, eventType, publicKey, encapsulation );
         } 
         catch( EOFException eof )
         {
           break;
         }
         catch( Exception ex )
         {
           String errMsg = "Error deserializing KyberExchangeMessage. Error = " + ex.getMessage();
           LOGGER.error( errMsg ); 
           throw new AvroTransformException( errMsg );
         }
       }
    
      LOGGER.info( "KyberExchangeMessage deserialize was successful" );

      return msg;
    } 
    catch( Exception e )
    {
      String errMsg = "Error encountered deserializing KyberExchangeMessgae. Error = " + e.getMessage();
      LOGGER.error( errMsg );
      throw new AvroTransformException( errMsg);
    }
  }   
}
